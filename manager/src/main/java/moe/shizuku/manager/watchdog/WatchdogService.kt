package moe.shizuku.manager.watchdog

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import moe.shizuku.manager.MainActivity
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.AdbStarter
import moe.shizuku.manager.starter.ServiceStartHelper
import moe.shizuku.manager.utils.EnvironmentUtils
import rikka.shizuku.Shizuku

/**
 * Watchdog foreground service that monitors the Shizuku server process.
 *
 * 后台保活（两条腿走路）：
 * - 系统级：前台常驻服务 + 划掉时 onTaskRemoved 立刻自拉起 + 闹钟兜底；
 * - ADB 级：shell 侧小看门（[KeepAliveDaemon]，走本机 ADB 5555 驻守），
 *   App 被系统杀掉时由它用 `am` 把服务拉回来；
 * - 断线重连三级策略：binder 死亡 → 3 秒后快重试（×3）→ 仍失败进入低频恢复
 *   （每 5 分钟探测 + 拉起一次，直到成功）；看门狗自己被拉起来而 binder 从未上报时
 *   （开机 / 闹钟拉回）走「冷恢复」主动探测一次；
 *   低频恢复**有出口**：当前配置下无从拉起（如 ADB 激活但 5555 不通又没法自动开无线调试）
 *   或试满 [RECOVERY_MAX_ATTEMPTS] 次就停表，通知改为如实说明「已放弃自动重连」，
 *   不再每 5 分钟空转；
 * - 手动「停止服务」通过 [noteIntentionalStop] 压制自动重连 30 分钟 ——
 *   保活不推翻主人的明确操作；
 * - 常驻通知上带「杀后台」按钮：想关的时候一键撤保活并完全退场。
 */
class WatchdogService : Service() {

    companion object {
        private const val TAG = "WatchdogService"
        private const val CHANNEL_ID = "watchdog"
        private const val NOTIFICATION_ID = 1002

        private const val RESTART_DELAY_MS = 3_000L
        private const val MAX_CONSECUTIVE_CRASHES = 3
        private const val CRASH_WINDOW_MS = 5 * 60 * 1000L

        /** 快重试（3 次 × 3s）拉不起来 → 进入低频重试：每 5 分钟探测并尝试拉起一次，直到 binder 回来。 */
        private const val RECOVERY_INTERVAL_MS = 5 * 60 * 1000L

        /**
         * 低频重试的尝试上限（12 次 ≈ 1 小时）。到顶就停表并如实告知，不再无限空转 ——
         * 无限每 5 分钟唤醒换不来成功，只换来电量与一条永远在「正在重试」的通知。
         * 重新激活（binder 回来）会把计数清零，所以正常使用不会碰到这个上限。
         */
        private const val RECOVERY_MAX_ATTEMPTS = 12

        /** 冷恢复：看门狗自己被拉起来时（开机 / 闹钟拉回 / 桥服务 poke），binder 从未上报过 —— 死亡回调不会来，只能主动探测。 */
        private const val COLD_RECOVERY_DELAY_MS = 5_000L

        /** 主人手动「停止服务」后，自动重连至少静默 30 分钟（看门狗的保活不应推翻主人的明确操作）。 */
        private const val SUPPRESS_AFTER_INTENTIONAL_STOP_MS = 30 * 60 * 1000L

        /**
         * 预期内的暂停窗口：AdbStarter 切 TCP 端口 / 重启 adbd 时，服务会短暂“死”一下。
         * 这个时间窗里收到的 binder 死亡事件看门狗直接放过，不当崩溃处理。
         */
        @Volatile
        private var expectedDeathUntilMs = 0L

        fun expectDeathWindow(ms: Long = 15_000L) {
            expectedDeathUntilMs = SystemClock.elapsedRealtime() + ms
        }

        private fun inExpectedDeathWindow(): Boolean =
            SystemClock.elapsedRealtime() < expectedDeathUntilMs

        /**
         * 手动「停止服务」前调用：既放过紧随其后的死亡回调，也把自动重连压 30 分钟 ——
         * 否则看门狗会在 3 秒后把刚停掉的服务又拉起来（死亡回调 = 崩溃，照样触发重连）。
         */
        fun noteIntentionalStop() {
            expectDeathWindow(30_000L)
            suppressAutoRestartUntilMs =
                SystemClock.elapsedRealtime() + SUPPRESS_AFTER_INTENTIONAL_STOP_MS
        }

        @Volatile
        private var suppressAutoRestartUntilMs = 0L

        private fun autoRestartSuppressed(): Boolean =
            SystemClock.elapsedRealtime() < suppressAutoRestartUntilMs

        private const val ACTION_STOP = "com.churan.shizako.action.WATCHDOG_STOP"
        private const val ACTION_KILL = "com.churan.shizako.action.WATCHDOG_KILL"

        /** 划掉 / 被杀后的闹钟自拉起（WatchdogRestartReceiver 接收）。 */
        private const val ACTION_RESTART = "com.churan.shizako.action.WATCHDOG_RESTART"
        private const val RESTART_REQUEST_CODE = 40
        private const val KILL_REQUEST_CODE = 41
        private const val RESTART_ALARM_DELAY_MS = 2_500L

        @Volatile
        var isRunning = false
            private set

        fun start(context: Context) {
            runCatching {
                val intent = Intent(context, WatchdogService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            runCatching {
                val intent = Intent(context, WatchdogService::class.java).apply { action = ACTION_STOP }
                context.startService(intent)
            }
        }

        /** 通知上的「杀后台」：撤保活 → 停服务 → 结束自己（App 完全退场）。 */
        fun killBackground(context: Context) {
            runCatching {
                val intent = Intent(context, WatchdogService::class.java).apply { action = ACTION_KILL }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        /** 划掉 / 被杀后的系统级兜底：闹钟几秒后把服务拉回来。 */
        fun scheduleRestart(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val at = SystemClock.elapsedRealtime() + RESTART_ALARM_DELAY_MS
            val pi = restartPendingIntent(context)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                    am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
                } else {
                    am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
                }
            } catch (_: Throwable) {
                runCatching { am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi) }
            }
        }

        fun cancelScheduledRestart(context: Context) {
            runCatching {
                val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
                am.cancel(restartPendingIntent(context))
            }
        }

        private fun restartPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, WatchdogRestartReceiver::class.java).setAction(ACTION_RESTART)
            return PendingIntent.getBroadcast(
                context, RESTART_REQUEST_CODE, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var wasRunning = false
    private var consecutiveCrashes = 0
    private var lastCrashTime = 0L
    /** 快重试没拉起来 → true：进入每 5 分钟一次的低频重试。 */
    private var inRecoveryMode = false
    private var recoveryAttempts = 0
    /** 恢复已停表：当前配置下拉不起来（无从下手）或已试满上限，通知需要如实说明。 */
    private var recoveryUnavailable = false

    private val restartRunnable: Runnable = Runnable {
        if (!Shizuku.pingBinder() && !autoRestartSuppressed()) {
            restartShizuku()
        }
    }

    /** 低频重试：失败不罢休，每 [RECOVERY_INTERVAL_MS] 探测并尝试拉起一次，binder 回来才停。 */
    private val recoveryRunnable: Runnable = Runnable {
        if (Shizuku.pingBinder()) {
            // binder 已被别处拉起（主人手动启动等）：退出恢复模式
            handler.removeCallbacks(recoveryRunnable)
            inRecoveryMode = false
            recoveryAttempts = 0
            recoveryUnavailable = false
        } else if (!autoRestartSuppressed()) {
            recoveryAttempts++
            if (!restartShizuku()) {
                // 当前配置下拉不起来（上次 ADB 激活、但 5555 不通又无法自动开无线调试）。
                // 继续每 5 分钟空转没有意义：停表并如实告知，别让通知一直宣称「正在重试」。
                giveUpRecovery("no viable restart path for last launch mode")
            } else if (recoveryAttempts >= RECOVERY_MAX_ATTEMPTS) {
                giveUpRecovery("exceeded $RECOVERY_MAX_ATTEMPTS low-frequency attempts")
            } else {
                handler.postDelayed(recoveryRunnable, RECOVERY_INTERVAL_MS)
            }
        } else {
            handler.postDelayed(recoveryRunnable, RECOVERY_INTERVAL_MS)
        }
        updateNotification()
    }

    /**
     * 停止低频恢复并如实汇报。
     *
     * 保留 [inRecoveryMode] = true 是为了让通知显示「已放弃自动重连」而不是倒回
     * 「已崩溃 %d 次，将重试…」——后者会让主人继续等一件不会发生的事。
     * 注意这里不清 [recoveryAttempts]：通知里要显示试了多少次。
     */
    private fun giveUpRecovery(reason: String) {
        Log.w(TAG, "Giving up low-frequency recovery: $reason")
        handler.removeCallbacks(recoveryRunnable)
        recoveryUnavailable = true
    }

    private lateinit var binderReceivedListener: Shizuku.OnBinderReceivedListener
    private lateinit var binderDeadListener: Shizuku.OnBinderDeadListener

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()

        binderReceivedListener = Shizuku.OnBinderReceivedListener {
            wasRunning = true
            consecutiveCrashes = 0
            lastCrashTime = 0L
            // 服务真的回来了 → 解除手动停止的压制（主人后来又手动启动过 = 恢复保活语义）
            suppressAutoRestartUntilMs = 0L
            handler.removeCallbacks(recoveryRunnable)
            inRecoveryMode = false
            recoveryAttempts = 0
            recoveryUnavailable = false
            updateNotification()
            // 服务回来了 → 确认 shell 小看门在岗（「用 ADB 保活」）
            KeepAliveDaemon.ensureRunningAsync(this)
        }

        binderDeadListener = Shizuku.OnBinderDeadListener {
            if (inExpectedDeathWindow()) {
                // 预期内的死亡（TCP 切换 / 重启 adbd / 手动停止）：就地消掉窗口，不安排重启
                expectedDeathUntilMs = 0L
                wasRunning = false
            } else if (wasRunning && !autoRestartSuppressed()) {
                val now = System.currentTimeMillis()
                if (now - lastCrashTime > CRASH_WINDOW_MS) {
                    consecutiveCrashes = 0
                }
                consecutiveCrashes++
                lastCrashTime = now

                if (consecutiveCrashes <= MAX_CONSECUTIVE_CRASHES) {
                    handler.postDelayed(restartRunnable, RESTART_DELAY_MS)
                } else {
                    // 快重试 3 次仍没回来：放弃高频重试，转入低频恢复（每 5 分钟一次，直到成功）
                    handler.removeCallbacks(recoveryRunnable)
                    inRecoveryMode = true
                    recoveryUnavailable = false
                    handler.postDelayed(recoveryRunnable, RECOVERY_INTERVAL_MS)
                }
                wasRunning = false
            } else {
                wasRunning = false
            }
            updateNotification()
        }

        Shizuku.addBinderReceivedListener(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)

        wasRunning = Shizuku.pingBinder()
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                teardownKeepAlive()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_KILL -> {
                teardownKeepAlive()
                stopSelf()
                android.os.Process.killProcess(android.os.Process.myPid())
                return START_NOT_STICKY
            }
        }

        startForeground(NOTIFICATION_ID, buildNotification())

        // 后台保活：确保 shell 小看门在岗（用 ADB spawn；拿不到 ADB / root 时静默失败，下次触发再试）
        KeepAliveDaemon.ensureRunningAsync(this)

        // 冷恢复：进程重启（开机 / 闹钟拉回 / 桥服务 poke）后 binder 从未上报 —— 死亡回调不会来，
        // 只能在这里主动探测一次。条件：以前确实成功激活过（mode 已记录）且主人没有刚手动停过服务。
        if (!Shizuku.pingBinder()
            && !inExpectedDeathWindow()
            && !autoRestartSuppressed()
            && ShizukuSettings.getLastLaunchMode() != ShizukuSettings.LaunchMethod.UNKNOWN
        ) {
            handler.postDelayed(restartRunnable, COLD_RECOVERY_DELAY_MS)
        }

        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (!ShizukuSettings.isWatchdogEnabled()) return

        // ① 系统级兜底：立刻尝试自拉起（能成就成）
        runCatching {
            val svc = Intent(this, WatchdogService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc) else startService(svc)
        }
        // ② 系统级兜底：几秒后闹钟再拉一次（防进程被秒杀）
        scheduleRestart(this)
        // ③ ADB 兜底：确保 shell 小看门在岗（它会在下一轮把 App 拉回来）
        KeepAliveDaemon.ensureRunningAsync(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacks(restartRunnable)
        handler.removeCallbacks(recoveryRunnable)
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        super.onDestroy()
    }

    /** 「杀后台」/ 关闭保活：撤掉闹钟与 shell 小看门（脚本每轮自检，看到 flag 没了就退出）。 */
    private fun teardownKeepAlive() {
        cancelScheduledRestart(this)
        KeepAliveDaemon.disable(this)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.watchdog_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val killPi = PendingIntent.getService(
            this, KILL_REQUEST_CODE,
            Intent(this, WatchdogService::class.java).setAction(ACTION_KILL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val running = Shizuku.pingBinder()
        val title = getString(R.string.watchdog_notification_title)
        val text = if (running) {
            getString(R.string.watchdog_notification_running)
        } else if (autoRestartSuppressed()) {
            // 主人刚手动停过服务：如实汇报「已按主人要求保持停止」，不假装在抢救
            getString(R.string.watchdog_notification_stopped_by_user)
        } else if (recoveryUnavailable) {
            // 恢复已停表：别再说「正在重试」，把可行的下一步告诉主人
            getString(R.string.watchdog_notification_recovery_gave_up)
        } else if (inRecoveryMode) {
            getString(R.string.watchdog_notification_recovering, recoveryAttempts)
        } else if (consecutiveCrashes > 0) {
            getString(R.string.watchdog_notification_crashed, consecutiveCrashes)
        } else {
            getString(R.string.watchdog_notification_waiting)
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setSmallIcon(R.drawable.ic_system_icon)
            .setColor(getColor(R.color.notification))
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .setShowWhen(false)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.watchdog_action_kill),
                killPi
            )
            .build()
    }

    private fun updateNotification() {
        try {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            nm.notify(NOTIFICATION_ID, buildNotification())
        } catch (_: Exception) {
        }
    }

    /**
     * 按上次成功的激活方式拉起服务。
     *
     * @return true 表示**确实发起了一次拉起尝试**（结果由 binder 回调给出）；false 表示
     * 当前配置下根本无从下手（例如上次是 ADB 激活，但 5555 不通、又没拿到
     * WRITE_SECURE_SETTINGS 无法自动开无线调试）。低频恢复循环必须看这个返回值 ——
     * 否则会每 5 分钟空转一次、永远等不到成功，而通知还在宣称「正在重试」。
     */
    private fun restartShizuku(): Boolean {
        return when (ShizukuSettings.getLastLaunchMode()) {
            ShizukuSettings.LaunchMethod.ROOT -> {
                ServiceStartHelper.startRoot()
                true
            }
            ShizukuSettings.LaunchMethod.ADB -> {
                if (ShizukuSettings.isTcpMode() && EnvironmentUtils.isAdbPortLive(AdbStarter.TCP_MODE_PORT)) {
                    // TCP 模式：本机 5555 还活着，直接连回来（不用网络、不用无线调试）——照搬 Shevery
                    CoroutineScope(Dispatchers.IO).launch {
                        runCatching {
                            AdbStarter.start("127.0.0.1", AdbStarter.TCP_MODE_PORT, applicationContext)
                        }
                    }
                    true
                } else if (ServiceStartHelper.canAdbAutoStart(this)) {
                    ServiceStartHelper.startAdb(this)
                    true
                } else {
                    // 无从下手：不能自动开无线调试，5555 也不通。交给调用方停表并如实告知。
                    Log.w(TAG, "Cannot restart: last launch mode is ADB but neither TCP 5555 nor auto wireless-debugging is available")
                    false
                }
            }
            else -> false
        }
    }
}