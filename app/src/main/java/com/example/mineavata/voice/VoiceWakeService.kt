package com.example.mineavata.voice

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.example.mineavata.BuildConfig
import com.example.mineavata.MainActivity
import com.example.mineavata.pet.PetService

/**
 * 语音唤醒前台服务（microphone 类型）：常驻监听 → 识别文本命中唤醒提示词 →
 * 震动 + 让桌宠随机播一个动作或表情。
 *
 * **为什么独立于 PetService**：语音监听的总开关与桌宠开关是两个独立开关；
 * 且 PetService 换模型/换尺寸会 `stopSelf()` 重启（重建 GL 窗口），不该打断监听。
 *
 * 注意 `START_NOT_STICKY`：microphone 类型的前台服务**不能从后台自启**，
 * 被系统杀掉后靠用户回到 App 时由 [ensureRunning] 拉起。
 */
class VoiceWakeService : Service() {

    private var detector: SpeechWakeDetector? = null
    private var wakeLock: PowerManager.WakeLock? = null

    /** 上次命中时间，用于冷却（动作播放期间不该被同一句话连续触发） */
    private var lastHitAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        // 权限检查必须在 startForeground **之前**：Android 14+ 要求以 microphone 类型进入
        // 前台时已持有 RECORD_AUDIO，否则 startForeground 抛 SecurityException。
        // 此时直接 stopSelf 是合法的 —— 系统只在"超时仍未调 startForeground"时才 ANR。
        if (!hasMicPermission(this)) {
            Log.w(TAG, "缺少 RECORD_AUDIO 权限，不启动监听")
            WakePrefs.setEnabled(this, false)
            stopSelf()
            return
        }

        val foregroundOk = runCatching { startForegroundCompat() }
            .onFailure { Log.e(TAG, "进入前台失败", it) }
            .isSuccess
        if (!foregroundOk) {
            stopSelf()
            return
        }

        isRunning = true
        acquireWakeLock()
        detector = SpeechWakeDetector(
            context = this,
            onText = { text -> onRecognized(text) },
            onFatal = { reason ->
                Log.w(TAG, "识别不可用：$reason")
                // 开着开关却监听不了是骗人的状态，回写关掉
                WakePrefs.setEnabled(this, false)
                stopSelf()
            },
        ).also { it.start() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // 通知栏"关闭"也要回写开关，否则 UI 上的开关会停在"开"而实际没在监听
            WakePrefs.setEnabled(this, false)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        detector?.stop()
        detector = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        isRunning = false
        super.onDestroy()
    }

    // ---------- 命中处理 ----------

    private fun onRecognized(text: String) {
        // 识别内容属于隐私：全量文本只进 debug 包日志，release 不落地
        if (!PhraseMatcher.matches(text, WakePrefs.getPhrase(this))) {
            if (BuildConfig.DEBUG) Log.d(TAG, "识别文本未命中：\"$text\"")
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastHitAt < COOLDOWN_MS) {
            Log.d(TAG, "命中但在冷却窗口内，忽略")
            return
        }
        lastHitAt = now
        Log.i(TAG, if (BuildConfig.DEBUG) "命中唤醒词：\"$text\"" else "命中唤醒词")
        vibrate()
        dispatchReaction()
    }

    /**
     * 让桌宠随机动一下。
     *
     * 桌宠没开时**不启动它**：用 startForegroundService 送 intent 会把桌宠凭空叫出来，
     * "喊一声冒出个宠物"不是预期行为——此时只保留震动反馈。
     *
     * 桌宠在跑时用 startService：本服务正在前台，属后台启动限制的豁免情形。
     */
    private fun dispatchReaction() {
        if (!PetService.isRunning) {
            Log.d(TAG, "桌宠未运行，仅震动不派发")
            return
        }
        val i = Intent(this, PetService::class.java).setAction(PetService.ACTION_REACT)
        runCatching { startService(i) }
            .onFailure { Log.w(TAG, "派发反应失败", it) }
    }

    /**
     * 只震动、不做 TTS：TTS 出声会被自己的麦克风录进去，可能自我触发。
     *
     * 整体 runCatching：震动只是附带反馈，失败（缺权限/无马达）不能把服务炸掉——
     * 反应派发才是正事（教训：漏声明 VIBRATE 权限曾把整个进程带崩）。
     */
    private fun vibrate() {
        runCatching {
            val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            } ?: return@runCatching
            if (!vib.hasVibrator()) return@runCatching
            vib.vibrate(VibrationEffect.createOneShot(VIBRATE_MS, VibrationEffect.DEFAULT_AMPLITUDE))
        }.onFailure { Log.w(TAG, "震动反馈失败（忽略，不影响反应派发）", it) }
    }

    // ---------- 前台服务 ----------

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MineAvata::VoiceWake").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "语音唤醒", NotificationManager.IMPORTANCE_MIN),
        )
        val contentIntent = PendingIntent.getActivity(
            this, 10, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this, 11,
            Intent(this, VoiceWakeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("MineAvata 语音唤醒监听中")
            .setContentIntent(contentIntent)
            .addAction(0, "关闭", stopIntent)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    companion object {
        private const val TAG = "VoiceWakeService"

        /** 通知 id 必须与 PetService 的错开：通知 id 是应用级命名空间 */
        private const val CHANNEL_ID = "voice_wake_service"
        private const val NOTIF_ID = 3
        private const val ACTION_STOP = "com.example.mineavata.action.VOICE_STOP"

        /** 命中冷却：动作要播几秒，窗口太短会被同一句话连续触发 */
        private const val COOLDOWN_MS = 4_000L
        private const val VIBRATE_MS = 60L

        @Volatile
        var isRunning: Boolean = false
            private set

        fun hasMicPermission(context: Context): Boolean =
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

        /** 语音监听总开关：保存意愿 + 启停服务（服务自己读 prefs，不需要 extra） */
        fun setListening(context: Context, enabled: Boolean) {
            WakePrefs.setEnabled(context, enabled)
            val i = Intent(context, VoiceWakeService::class.java)
            if (enabled) context.startForegroundService(i)
            else context.stopService(i)
        }

        /**
         * 回前台时的一致性恢复：prefs 说该开、权限也在、但服务没跑（被系统杀了）→ 拉起。
         *
         * 这是 microphone 前台服务**唯一可靠的重启点**——此刻 App 在前台才允许启动。
         * 权限被撤销的情况顺手把开关回写关掉，避免开关停在"开"却什么都没发生。
         */
        fun ensureRunning(context: Context) {
            if (!WakePrefs.isEnabled(context)) return
            if (!hasMicPermission(context)) {
                WakePrefs.setEnabled(context, false)
                return
            }
            if (isRunning) return
            runCatching { context.startForegroundService(Intent(context, VoiceWakeService::class.java)) }
                .onFailure { Log.w(TAG, "恢复语音监听失败", it) }
        }
    }
}