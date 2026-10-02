package com.example.mineavata.pet

import android.content.Context
import android.net.Uri
import android.os.PowerManager
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

/**
 * 模型导入流水线（应用级单例，不随 Activity 生死）：
 *
 *   拷贝 → 检查 → 解压 → 校验 → 贴图优化 → 安装（rename）
 *
 * 全程在 Dispatchers.IO 后台执行，UI 线程零阻塞（旧版在主线程同步跑，
 * 大包直接黑屏十几秒还有 ANR 风险）。进度经 [state] 发射，UI 观察渲染对话框；
 * 支持取消、WakeLock 防息屏中断、进程重启孤儿清扫兜底。
 *
 * 两个结构性改进（相对旧的流式导入）：
 * 1. **先落盘 zip 到 cacheDir**：GetContent 的 URI 读权限随 Activity 任务结束失效，
 *    落盘后彻底解耦；还换来 ZipFile 随机访问 → 中央目录预判条目名编码，只解压一遍
 *    （旧逻辑：流式解压 → 引用对不上 → 删掉换编码全重来）。
 * 2. **解压到 filesDir/import_staging/ 目录**（刻意在 live2d/ 外面，scan() 看不到半成品），
 *    校验通过后同分区 rename 原子移入 live2d/——消掉旧的"cache → files 全量二次拷贝"，
 *    且 live2d/ 里永远只有完整模型（失败/取消/断电零污染）。
 */
object ImportManager {

    /** 导入进度状态机（UI 渲染用；终态 Success/Failure/Cancelled 由 UI [consumeTerminal] 收尾） */
    sealed interface State {
        /** 无任务 */
        data object Idle : State
        /** 拷贝 zip 进私有目录；total<=0 = provider 给不出大小（只显示已拷量） */
        data class Copying(val bytes: Long, val total: Long) : State
        /** 读中央目录：魔数/编码预判/磁盘空间检查 */
        data object Inspecting : State
        /** 解压到 staging（字节级进度 + 当前文件名） */
        data class Extracting(
            val bytes: Long,
            val totalBytes: Long,
            val fileIndex: Int,
            val fileCount: Int,
            val current: String,
        ) : State
        /** sanitize + 模型引用校验 */
        data object Validating : State
        /** 超限贴图重压缩（≤limit 的秒过） */
        data class Textures(val done: Int, val total: Int, val current: String) : State
        /** rename 进 live2d/ */
        data object Installing : State
        data class Success(val name: String) : State
        data class Failure(val reason: String) : State
        data object Cancelled : State
    }

    private const val TAG = "ImportManager"

    /** 进度发射节流：StateFlow 每 64KB 一块都发会重组风暴 */
    private const val EMIT_INTERVAL_MS = 100L

    /** WakeLock 超时兜底，绝不无限持有 */
    private const val WAKELOCK_TIMEOUT_MS = 10 * 60 * 1000L

    private const val BUFFER = 64 * 1024

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastEmitAt = 0L
    private val swept = AtomicBoolean(false)

    /** 单飞判断：导入进行中拒绝第二个（start/busy 都只在主线程调，无竞态） */
    val busy: Boolean get() = job?.isActive == true

    /** 启动导入。只从主线程调用；[busy] 时直接忽略。 */
    fun start(context: Context, uri: Uri) {
        if (busy) return
        val app = context.applicationContext
        // 立即上屏（拷贝阶段占位），不等 IO 线程调度
        _state.value = State.Copying(0, -1)
        job = scope.launch {
            acquireWakeLock(app)
            try {
                runImport(app, uri)
            } catch (e: CancellationException) {
                _state.value = State.Cancelled
            } catch (e: ModelStorage.ImportException) {
                Log.w(TAG, "导入失败: ${e.message}")
                _state.value = State.Failure(e.message ?: "未知错误")
            } catch (e: Exception) {
                Log.e(TAG, "导入失败", e)
                _state.value = State.Failure(e.message ?: e.javaClass.simpleName)
            } finally {
                releaseWakeLock()
            }
        }
    }

    /** 取消当前导入（协作式，检查点粒度 = 每个缓冲块/每个文件）；临时文件由 runImport 的 finally 清理 */
    fun cancel() {
        job?.cancel()
    }

    /** UI 处理完终态（Success/Failure/Cancelled）后调，回到 Idle 关对话框；非终态忽略 */
    fun consumeTerminal() {
        when (val s = _state.value) {
            is State.Success, is State.Failure, State.Cancelled -> _state.value = State.Idle
            else -> Unit
        }
    }

    /**
     * 清理上次进程中断（崩溃/被杀/断电）遗留的孤儿临时文件。
     * Application.onCreate 调用，每进程一次；此时必然没有导入在跑，整目录递归删安全。
     */
    fun sweepOrphans(context: Context) {
        if (!swept.compareAndSet(false, true)) return
        val app = context.applicationContext
        scope.launch {
            runCatching { zipDir(app).deleteRecursively() }
            runCatching { stagingRoot(app).deleteRecursively() }
        }
    }

    private suspend fun runImport(context: Context, uri: Uri) {
        val zip = File(zipDir(context), "${UUID.randomUUID()}.zip")
        val staging = File(stagingRoot(context), UUID.randomUUID().toString())
        val job = coroutineContext[Job]!!
        try {
            // ---- 1. 拷贝进私有目录（URI 授权解耦 + 后续 ZipFile 随机访问的前提） ----
            val total = querySize(context, uri)
            zipDir(context).mkdirs()
            val input = context.contentResolver.openInputStream(uri)
                ?: throw ModelStorage.ImportException("无法读取所选文件（授权可能已失效）")
            input.use { i ->
                zip.outputStream().use { o ->
                    val buf = ByteArray(BUFFER)
                    var done = 0L
                    while (true) {
                        job.ensureActive()
                        val n = i.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        done += n
                        emitThrottled(State.Copying(done, total))
                    }
                }
            }
            if (zip.length() < 4) throw ModelStorage.ImportException("压缩包为空或读取失败")

            // ---- 2. 检查：魔数 + 编码预判 + 磁盘空间预检 ----
            emit(State.Inspecting)
            job.ensureActive()
            val plan = ModelStorage.inspectZip(zip, context)

            // ---- 3. 解压到 staging ----
            staging.mkdirs()
            ModelStorage.extractFromFile(zip, plan, staging) { bytes, totalBytes, idx, count, name ->
                job.ensureActive()
                emitThrottled(State.Extracting(bytes, totalBytes, idx, count, name))
            }

            // ---- 4. 校验（定位 model3.json + sanitize + 引用检查） ----
            emit(State.Validating)
            job.ensureActive()
            val (modelDir, modelJson) = ModelStorage.validateStaging(staging)

            // ---- 5. 贴图优化（仅超限 PNG 才真的重编码） ----
            val limit = ModelStorage.getTextureLimit(context)
            if (limit == 2048 || limit == 4096) {
                ModelStorage.applyTextureLimit(modelDir, limit) { done, t, name ->
                    job.ensureActive()
                    emitThrottled(State.Textures(done, t, name))
                }
            }

            // ---- 6. 安装：同分区 rename 原子移入 live2d/ ----
            emit(State.Installing)
            job.ensureActive()
            val name = ModelStorage.installStaged(context, modelDir, modelJson)
            emit(State.Success(name))
        } finally {
            runCatching { zip.delete() }
            runCatching { staging.deleteRecursively() }
        }
    }

    /** 压缩包总大小：AFD 长度优先，OpenableColumns.SIZE 兜底，都拿不到返回 -1（进度转不确定） */
    private fun querySize(context: Context, uri: Uri): Long {
        runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                if (afd.length > 0) return afd.length
            }
        }
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0 && c.moveToFirst() && !c.isNull(idx)) return c.getLong(idx)
            }
        }
        return -1L
    }

    private fun emit(s: State) {
        lastEmitAt = SystemClock.elapsedRealtime()
        _state.value = s
    }

    private fun emitThrottled(s: State) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastEmitAt >= EMIT_INTERVAL_MS) emit(s)
    }

    private fun acquireWakeLock(context: Context) {
        if (wakeLock?.isHeld == true) return
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MineAvata::ModelImport").apply {
            setReferenceCounted(false)
            acquire(WAKELOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    /** zip 落盘目录（cacheDir：系统空间紧张时可回收，导入完成后主动删） */
    private fun zipDir(context: Context) = File(context.cacheDir, "imports")

    /** 解压 staging 根（filesDir 下、live2d/ 外——半成品不能被 scan() 看到） */
    private fun stagingRoot(context: Context) = File(context.filesDir, "import_staging")
}
