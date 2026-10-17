package moe.shizuku.manager.adb

import android.app.ActivityOptions
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.lifecycle.Observer
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import moe.shizuku.manager.MainActivity
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.starter.ServiceStartHelper
import rikka.core.ktx.unsafeLazy
import rikka.shizuku.Shizuku
import java.net.ConnectException
import javax.net.ssl.SSLException

@RequiresApi(Build.VERSION_CODES.R)
class AdbPairingService : Service() {

    companion object {

        const val NOTIFICATIONCHANNEL = "adb_pairing"

        private const val TAG = "AdbPairingService"

        private const val NOTIFICATIONID = 1
        private const val REPLYREQUESTID = 1
        private const val STOPREQUESTID = 2
        private const val ACTIVATIONREQUESTID = 4
        private const val STARTACTION = "start"
        private const val STOPACTION = "stop"
        private const val REPLYACTION = "reply"
        private const val REMOTEIMPUTRESULTKEY = "paring_code"
        private const val PORTKEY = "adb_pairing_port"

        fun startIntent(context: Context): Intent {
            return Intent(context, AdbPairingService::class.java).setAction(STARTACTION)
        }

        private fun stopIntent(context: Context): Intent {
            return Intent(context, AdbPairingService::class.java).setAction(STOPACTION)
        }

        private fun replyIntent(context: Context, port: Int): Intent {
            return Intent(context, AdbPairingService::class.java).setAction(REPLYACTION).putExtra(PORTKEY, port)
        }
    }

    private var adbMdns: AdbMdns? = null
    private var pairingHost: String? = null

    private val observer = Observer<AdbServiceEndpoint> { endpoint ->
        pairingHost = endpoint.host
        val port = endpoint.port
        Log.i(TAG, "Pairing service port: $port")
        if (port <= 0) return@Observer

        // Since the service could be killed before user finishing input,
        // we need to put the port into Intent
        val notification = createInputNotification(port)

        getSystemService(NotificationManager::class.java).notify(NOTIFICATIONID, notification)
    }

    private var started = false

    override fun onCreate() {
        super.onCreate()

        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                NOTIFICATIONCHANNEL,
                getString(R.string.notification_channel_adb_pairing),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                setSound(null, null)
                setShowBadge(false)
                setAllowBubbles(false)
            })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = when (intent?.action) {
            STARTACTION -> {
                onStart()
            }
            REPLYACTION -> {
                val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(REMOTEIMPUTRESULTKEY) ?: ""
                val port = intent.getIntExtra(PORTKEY, -1)
                if (port != -1) {
                    onInput(code.toString(), port)
                } else {
                    onStart()
                }
            }
            STOPACTION -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                null
            }
            else -> {
                return START_NOT_STICKY
            }
        }
        if (notification != null) {
            try {
                startForeground(NOTIFICATIONID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST)
            } catch (e: Throwable) {
                Log.e(TAG, "startForeground failed", e)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    && e is ForegroundServiceStartNotAllowedException) {
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATIONID, notification)
                }
            }
        }
        return START_REDELIVER_INTENT
    }

    private fun startSearch() {
        if (started) return
        started = true
        adbMdns = AdbMdns(this, AdbMdns.TLS_PAIRING, observer).apply { start() }
    }

    private fun stopSearch() {
        if (!started) return
        started = false
        adbMdns?.stop()
    }

    override fun onTimeout(startId: Int) {
        // A shortService foreground service must stop within ~3 minutes,
        // otherwise the system raises an ANR. Stop everything gracefully.
        super.onTimeout(startId)
        stopSearch()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSearch()
    }

    private fun onStart(): Notification {
        startSearch()
        return searchingNotification
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun onInput(code: String, port: Int): Notification {
        GlobalScope.launch(Dispatchers.IO) {
            // 候选地址：回环优先，回环连不上时改用本机局域网地址兜底
            // （部分设备/ROM 上应用连不上自己的 127.0.0.1，见 AdbPairingClient 类注释）
            val hosts = AdbPairingClient.candidateHosts(pairingHost)

            val preferences = ShizukuSettings.getPreferences()
            // 配对期间的密钥只留内存，配对成功才落盘 —— 一次失败的尝试不该留在本地，
            // 否则激活页会把它当成「已配对」的证据（adbd 根本不认识它）
            val keyStore = DeferredAdbKeyStore(PreferenceAdbKeyStore(preferences))
            val key = try {
                AdbKey(keyStore, "shizuku")
            } catch (e: Throwable) {
                e.printStackTrace()
                // 以前这里直接 return，通知会永远停在「配对中」，用户等不出任何结果
                handleResult(false, AdbKeyException(e))
                return@launch
            }

            // use{} 保证 socket 与原生配对上下文一定被回收（start() 内部也做了 finally 清理，
            // 这里再套一层是防止将来有人改动 start() 的清理逻辑）。原先这里既没 use 也没 close：
            // 每次配对（含失败重试）都泄漏两个 fd + 一块持有 SPAKE2 密钥的原生内存。
            val result = runCatching {
                AdbPairingClient(hosts, port, code, key).use { client ->
                    client.start()
                }
            }
            result.onFailure {
                handleResult(false, it)
            }.onSuccess {
                keyStore.commit()
                AdbPairingState.markPaired(preferences)
                handleResult(true, null)
            }
        }

        return workingNotification
    }

    private fun handleResult(success: Boolean, exception: Throwable?) {
        stopForeground(STOP_FOREGROUND_REMOVE)

        val title: String
        val text: String?

        if (success) {
            Log.i(TAG, "Pair succeed")

            title = getString(R.string.notification_adb_pairing_succeed_title)
            text = getString(R.string.notification_adb_pairing_succeed_text)

            stopSearch()
        } else {
            title = getString(R.string.notification_adb_pairing_failed_title)

            text = when (exception) {
                is ConnectException -> {
                    getString(R.string.cannot_connect_port)
                }
                is AdbInvalidPairingCodeException -> {
                    getString(R.string.paring_code_is_wrong)
                }
                is AdbKeyException -> {
                    getString(R.string.adb_error_key_store)
                }
                is AdbPairingFailedException -> {
                    getString(R.string.pairing_failed_protocol)
                }
                is SSLException -> {
                    getString(R.string.pairing_handshake_failed)
                }
                else -> {
                    // 原因不明时给一句能照做的提示，别把堆栈甩给用户
                    getString(R.string.pairing_failed_unknown)
                }
            }

            if (exception != null) {
                Log.w(TAG, "Pair failed", exception)
            } else {
                Log.w(TAG, "Pair failed")
            }
        }

        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATIONID,
            Notification.Builder(this, NOTIFICATIONCHANNEL)
                .setColor(getColor(R.color.notification))
                .setSmallIcon(R.drawable.ic_system_icon)
                .setContentTitle(title)
                .setContentText(text)
                .apply {
                    if (success) {
                        // 配对成功：点通知本体或点按钮都直接落到「一站式激活」页
                        setContentIntent(activationPendingIntent)
                        setAutoCancel(true)
                        addAction(enterActivationAction)
                    }
                    /*if (!success) {
                        addAction(retryNotificationAction)
                    }*/
                }
                .build()
        )

        // 配对码输入完成 → 自动跳转「一站式激活」页，并直接跑启动脚本把服务拉起来
        // （配对只是配对；「激活」= 用无线调试跑 [Starter.internalCommand] 起服务）
        if (success) {
            openActivationPage()
            startServiceAfterPairing()
        }

        stopSelf()
    }

    /**
     * 配对成功后自动「激活」：走无线调试把启动脚本跑一遍（[ServiceStartHelper.startAdb]），
     * 服务起来后把通知改成「服务已启动」；起不来就保留「配对成功」那条通知，
     * 主人点「进入激活」可以手动再试。
     */
    private fun startServiceAfterPairing() {
        notifySimple(R.string.notification_adb_pairing_starting_service)

        ServiceStartHelper.startAdb(this) {
            var running = Shizuku.pingBinder()
            var waited = 0L
            while (!running && waited < 8000) {
                try {
                    Thread.sleep(250)
                } catch (_: InterruptedException) {
                    break
                }
                waited += 250
                running = Shizuku.pingBinder()
            }

            if (running) {
                notifySimple(R.string.notification_adb_pairing_service_started, withActivationAction = true)
            } else {
                Log.w(TAG, "Service did not come up after pairing")
            }
        }
    }

    private fun notifySimple(titleRes: Int, withActivationAction: Boolean = false) {
        val builder = Notification.Builder(this, NOTIFICATIONCHANNEL)
            .setColor(getColor(R.color.notification))
            .setSmallIcon(R.drawable.ic_system_icon)
            .setContentTitle(getString(titleRes))
        if (withActivationAction) {
            builder.setContentIntent(activationPendingIntent)
                .setAutoCancel(true)
                .addAction(enterActivationAction)
        }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATIONID, builder.build())
    }

    /**
     * 配对成功后自动打开激活页。
     *
     * 输入配对码时通知栏是展开的、Shizako 仍然是可见窗口，所以直接 startActivity 一般放行；
     * 万一被后台启动限制拦住，成功通知上还挂了 [activationPendingIntent] / [enterActivationAction]，
     * 用户点一下同样进激活页。
     */
    private fun openActivationPage() {
        // 首次引导还没完成时不打扰：主界面此刻会重定向回引导页，
        // 而引导页自己的「叫醒她」步骤会实时显示状态。
        if (!ShizukuSettings.isSetupCompleted()) return

        val intent = MainActivity.destinationIntent(this, R.id.activation_fragment)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivity(
                    intent,
                    ActivityOptions.makeBasic()
                        .setPendingIntentBackgroundActivityStartMode(
                            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                        )
                        .toBundle()
                )
            } else {
                startActivity(intent)
            }
        }.onFailure {
            Log.w(TAG, "Unable to open activation page", it)
        }
    }

    private val activationPendingIntent by unsafeLazy {
        PendingIntent.getActivity(
            this,
            ACTIVATIONREQUESTID,
            MainActivity.destinationIntent(this, R.id.activation_fragment),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_IMMUTABLE
            else
                0
        )
    }

    private val enterActivationAction by unsafeLazy {
        Notification.Action.Builder(
            null,
            getString(R.string.notification_adb_pairing_enter_activation),
            activationPendingIntent
        ).build()
    }

    private val stopNotificationAction by unsafeLazy {
        val pendingIntent = PendingIntent.getService(
            this,
            STOPREQUESTID,
            stopIntent(this),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_IMMUTABLE
            else
                0
        )

        Notification.Action.Builder(
            null,
            getString(R.string.notification_adb_pairing_stop_searching),
            pendingIntent
        )
            .build()
    }

    private fun replyNotificationAction(port: Int): Notification.Action {
        val pendingIntent = PendingIntent.getForegroundService(
            this,
            REPLYREQUESTID,
            replyIntent(this, port),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else
                PendingIntent.FLAG_UPDATE_CURRENT
        )

        return Notification.Action.Builder(
            null,
            getString(R.string.notification_adb_pairing_input_paring_code),
            pendingIntent
        )
            .addRemoteInput(
                RemoteInput.Builder(REMOTEIMPUTRESULTKEY).run {
                    setLabel(getString(R.string.dialog_adb_pairing_paring_code))
                    build()
                }
            )
            .build()
    }

    private val searchingNotification by unsafeLazy {
        Notification.Builder(this, NOTIFICATIONCHANNEL)
            .setColor(getColor(R.color.notification))
            .setSmallIcon(R.drawable.ic_system_icon)
            .setContentTitle(getString(R.string.notification_adb_pairing_searching_for_service_title))
            .addAction(stopNotificationAction)
            .build()
    }

    private fun createInputNotification(port: Int): Notification {
        return Notification.Builder(this, NOTIFICATIONCHANNEL)
            .setColor(getColor(R.color.notification))
            .setContentTitle(getString(R.string.notification_adb_pairing_service_found_title))
            .setSmallIcon(R.drawable.ic_system_icon)
            .addAction(replyNotificationAction(port))
            .build()
    }

    private val workingNotification by unsafeLazy {
        Notification.Builder(this, NOTIFICATIONCHANNEL)
            .setColor(getColor(R.color.notification))
            .setContentTitle(getString(R.string.notification_adb_pairing_working_title))
            .setSmallIcon(R.drawable.ic_system_icon)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}