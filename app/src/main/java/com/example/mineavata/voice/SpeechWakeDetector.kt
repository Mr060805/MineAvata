package com.example.mineavata.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

/**
 * 系统 `SpeechRecognizer` 的持续监听封装。
 *
 * `SpeechRecognizer` 一次只跑一轮：说完（onResults）或静音超时（onError）后都必须重新
 * `startListening` —— 这是"常驻监听"的全部秘密。
 *
 * 所有调用都在主线程（framework 要求 SpeechRecognizer 的方法都在主线程调用），
 * 重启循环因此挂在 `Handler(Looper.getMainLooper())` 上。
 *
 * @param onText  识别出的文本（整句，final 与 partial 都会回调）
 * @param onFatal 不可恢复的错误（无识别服务 / 权限被撤销）——上层应停止监听
 */
class SpeechWakeDetector(
    private val context: Context,
    private val onText: (String) -> Unit,
    private val onFatal: (String) -> Unit,
) {

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var running = false

    /** 连续的非正常错误次数，用于退避（防耗电热循环） */
    private var consecutiveErrors = 0

    /** 累计静音超时轮数（心跳日志用：证明识别循环还活着） */
    private var silentRounds = 0

    private val restartRunnable = Runnable { startListening() }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        /** 中间结果先送一轮：显著降低"喊完到反应"的延迟 */
        override fun onPartialResults(partialResults: Bundle?) {
            emit(partialResults)
        }

        override fun onResults(results: Bundle?) {
            consecutiveErrors = 0
            emit(results)
            scheduleRestart(RESTART_DELAY_MS)
        }

        override fun onError(error: Int) {
            when (error) {
                // 静音超时 / 没识别出内容：常驻监听下的正常现象，短延时继续
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    consecutiveErrors = 0
                    // 心跳日志：区分"识别器活着但一直没听到人声"与"识别器死了"
                    silentRounds++
                    if (silentRounds % HEARTBEAT_ROUNDS == 1) {
                        Log.d(TAG, "监听中：第 $silentRounds 轮静音超时，正常重启")
                    }
                    scheduleRestart(RESTART_DELAY_MS)
                }

                // 权限被撤销：重试也没用，交回上层收摊
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    Log.w(TAG, "麦克风权限不可用，停止监听")
                    running = false
                    onFatal("麦克风权限不可用")
                }

                // BUSY / CLIENT / NETWORK / AUDIO 等：稍长延时，连续失败则逐步退避
                else -> {
                    consecutiveErrors++
                    val delay = RESTART_DELAY_MS * consecutiveErrors.coerceAtMost(MAX_BACKOFF_STEPS)
                    Log.w(TAG, "识别出错 error=$error，${delay}ms 后重启（连续 $consecutiveErrors 次）")
                    scheduleRestart(delay)
                }
            }
        }
    }

    fun start() {
        if (running) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            // 设备没有识别服务（部分精简 ROM / 无 Google 服务的机器）
            onFatal("系统没有可用的语音识别服务")
            return
        }
        logAvailableEngines()
        running = true
        consecutiveErrors = 0
        silentRounds = 0
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(listener)
        }
        startListening()
    }

    /**
     * 诊断日志：列出设备上全部识别服务。
     * `createSpeechRecognizer` 绑哪家由系统默认决定，不同引擎（Google/讯飞/OEM 助手）
     * 行为差异巨大——出问题时先看这条。
     */
    private fun logAvailableEngines() {
        runCatching {
            val services = context.packageManager.queryIntentServices(
                Intent(RecognitionService.SERVICE_INTERFACE), 0,
            )
            Log.i(TAG, "设备识别服务: " + services.joinToString { "${it.serviceInfo?.packageName}/${it.serviceInfo?.name}" })
        }
    }

    fun stop() {
        running = false
        handler.removeCallbacks(restartRunnable)
        recognizer?.let {
            runCatching { it.stopListening() }
            runCatching { it.cancel() }
            runCatching { it.destroy() }
        }
        recognizer = null
    }

    /** 仅在主线程调用 */
    private fun startListening() {
        if (!running) return
        val r = recognizer ?: return
        runCatching { r.startListening(buildIntent()) }
            .onFailure {
                Log.w(TAG, "startListening 失败", it)
                scheduleRestart(RESTART_DELAY_MS)
            }
    }

    private fun scheduleRestart(delayMs: Long) {
        if (!running) return
        handler.removeCallbacks(restartRunnable)
        handler.postDelayed(restartRunnable, delayMs)
    }

    /** 取最高置信度的候选（第一个非空项） */
    private fun emit(bundle: Bundle?) {
        val list = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
        val text = list.firstOrNull { it.isNotBlank() } ?: return
        onText(text)
    }

    private fun buildIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Google 引擎按此包名校验 RECORD_AUDIO：不带它会在明明持有权限时
            // 立刻回 ERROR_INSUFFICIENT_PERMISSIONS（realme + GoogleRecognitionService 实测）
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }

    private companion object {
        const val TAG = "SpeechWakeDetector"

        /** 一轮结束后的重启延时：太短会撞上识别器还没释放，太长会漏掉紧接着说的话 */
        const val RESTART_DELAY_MS = 300L

        /** 连续出错时的最大退避倍数（300ms × 8 = 2.4s） */
        const val MAX_BACKOFF_STEPS = 8

        /** 每多少轮静音超时打一条心跳日志 */
        const val HEARTBEAT_ROUNDS = 20
    }
}