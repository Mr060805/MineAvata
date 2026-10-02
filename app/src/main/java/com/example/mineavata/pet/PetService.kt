package com.example.mineavata.pet

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import com.arkueid.alive2d.Live2D
import com.arkueid.alive2d.Live2DModel
import com.arkueid.alive2d.MotionPriority
import com.example.mineavata.MainActivity
import java.io.File
import kotlin.random.Random

/**
 * 桌面宠物悬浮窗前台服务。
 *
 * 模型无关：启动时扫描 filesDir/live2d/（ModelStorage），ModelAutoMapper 自动建立
 * "部位→动作/表情"映射。模型来源 = 内置 assets 释放 + 用户 zip 导入。
 */
class PetService : Service() {

    private var windowManager: WindowManager? = null
    private var petView: Live2DView? = null
    private var model: Live2DModel? = null
    private var mapping: ModelAutoMapper.ModelMapping? = null
    private var setupDone = false

    /** Cubism 表情是叠加层，设了不会自己消失；展示几秒后渐变回默认脸 */
    private val mainHandler = Handler(Looper.getMainLooper())
    private var expressionResetPending = false
    private var lastAppliedSize = 0f

    /**
     * 当前窗口里实际加载的模型目录名（内存态）。切模型检测必须比它，**不要比 prefs**——
     * MainActivity.useModel 会先写 prefs 再发 Intent，比 prefs 永远相等，
     * 重启分支成死代码（历史坑：点"使用"后悬浮窗不换模型，只有 UI 列表变了）。
     */
    private var loadedModel: String? = null

    private val prefs: SharedPreferences
        get() = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private var downRawX = 0f
    private var downRawY = 0f
    private var downViewX = 0
    private var downViewY = 0
    private var moved = false

    /** DOWN 点是否命中角色网格轮廓：决定本手势能否拖窗/触发动作（空白区只注视） */
    private var downOnBody = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        startForegroundCompat()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        ModelStorage.ensureBuiltinModels(this)
        setupPetWindow()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 语音唤醒命中触发的随机反应：就地消费。
        // 必须放在最前面 —— 下面两个 extra 分支是"参数变化 → stopSelf + 重启服务"语义，
        // 落进去会重建 GL 窗口，宠物会闪一下。
        if (intent?.action == ACTION_REACT) {
            if (setupDone) performRandomReaction()
            return START_STICKY
        }
        intent?.getStringExtra(EXTRA_MODEL)?.let { newModel ->
            // 记录选择（未运行时 onCreate 会读它；MainActivity 预写过也无害，幂等）
            prefs.edit().putString(PREF_MODEL, newModel).apply()
            // 切换模型：窗口已建好且目标 ≠ 当前实际加载的 → 重启服务重建渲染窗口
            if (setupDone && newModel != loadedModel) {
                stopSelf()
                startForegroundService(Intent(this, PetService::class.java))
                return START_NOT_STICKY
            }
        }
        intent?.getFloatExtra(EXTRA_SIZE, -1f)?.takeIf { it > 0 }?.let { newSize ->
            Log.i(TAG, "收到大小调整: extra=$newSize 已应用=$lastAppliedSize setupDone=$setupDone")
            // setPetSize 已把新值写入 prefs；若服务在跑且当前窗口尺寸还是旧值 → 重启应用
            if (setupDone && kotlin.math.abs(newSize - lastAppliedSize) > 0.001f) {
                stopSelf()
                startForegroundService(Intent(this, PetService::class.java))
                return START_NOT_STICKY
            }
        }
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupPetWindow() {
        Live2D.init()

        // 自动发现并解析模型（读用户上次选择；无效则取第一个可用的）
        val allDirs = ModelStorage.scan(this).map { ModelStorage.modelsRoot(this).resolve(it) }
        val wanted = prefs.getString(PREF_MODEL, null)
        val chosen = allDirs.firstOrNull { it.name == wanted } ?: allDirs.firstOrNull()
        val mapping = chosen?.let { ModelAutoMapper.map(this, it) }
        if (mapping == null) {
            Log.e(TAG, "live2d 目录下没有可用模型")
            stopSelf()
            return
        }
        this.mapping = mapping
        loadedModel = chosen?.name

        val sizeRatio = prefs.getFloat(PREF_SIZE, DEFAULT_SIZE_RATIO)
        lastAppliedSize = sizeRatio
        // 语义：窗口高度 = 宠物最大伸展高度
        val nominal = (resources.displayMetrics.widthPixels * sizeRatio).toInt()

        val m = Live2DModel()
        m.loadModelJson(mapping.modelJsonFile.absolutePath)
        model = m

        // 包络必须在渲染开始前算：扫描会重置动作与参数状态。
        // 结果按模型目录缓存，正常情况下只有首次（导入后第一次启动）才真的扫一遍。
        val fit = ModelEnvelope.obtain(mapping.modelDir, m)?.solveWindow(nominal)
        if (fit != null) {
            m.setScale(fit.scale)
            m.setOffset(fit.offsetX, fit.offsetY)
        } else {
            // 拿不到包络 → 退回旧的「正方形 + 0.9 缩放」行为，宁可留白也不要裁掉宠物
            Log.w(TAG, "包络不可用，退化为正方形窗口")
            m.setScale(0.9f)
        }
        val winW = fit?.width ?: nominal
        val winH = fit?.height ?: nominal

        val params = WindowManager.LayoutParams(
            winW,
            winH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = resources.displayMetrics.widthPixels / 2 - winW / 2
            y = resources.displayMetrics.heightPixels / 3
        }

        val view = Live2DView(this)
        view.setModel(m)

        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downViewX = params.x
                    downViewY = params.y
                    moved = false
                    // MotionEvent 在分发返回后会被回收进对象池：坐标必须在 UI 线程同步
                    // 捕获成局部变量，queueModel 的异步闭包里再读 event.x/y 会拿到脏值
                    val x = event.x
                    val y = event.y
                    // 轮廓门（逐三角形网格命中，精确到角色边缘）：本体 → 本手势允许拖窗/触发动作；
                    // 窗口内空白 → 只注视不拖不动作。
                    // 注：同步 JNI 读到的是 GL 线程上一帧的顶点，命中判定容忍一帧滞后；
                    // 透明区的点击本来就会被本窗口吞掉（Android overlay 无法穿透），不如让角色看一眼
                    downOnBody = model?.isAnyDrawableHit(x, y) ?: false
                    // 注视从按下就开始：CubismTargetPoint 限速追踪需要时间转头，
                    // 快速 tap 的 DOWN→UP 只有几十毫秒，等到 UP 才设目标就"来不及看"了
                    view.queueModel { it.drag(x, y) }
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (downOnBody && (moved || dx * dx + dy * dy > MOVE_THRESHOLD * MOVE_THRESHOLD)) {
                        moved = true
                        params.x = downViewX + dx.toInt()
                        params.y = downViewY + dy.toInt()
                        windowManager?.updateViewLayout(view, params)
                    } else if (!moved) {
                        // 阈值内小幅移动（或空白区任意移动）：注视跟随手指（官方 OnTouchesMoved 行为）
                        val x = event.x
                        val y = event.y
                        view.queueModel { it.drag(x, y) }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    // moved/downOnBody/坐标都在 UI 线程同步捕获：queueModel 异步执行，
                    // 闭包里读字段或 event 会和下一次 DOWN / MotionEvent 回收竞态
                    val wasMoved = moved
                    val onBody = downOnBody
                    val x = event.x
                    val y = event.y
                    view.queueModel { model ->
                        if (!wasMoved && onBody) {
                            // 点在本体 → 部位命中查自动映射表：有动作播动作，无动作切表情，都没有随机兜底
                            val hit = model.hitPart(x, y).firstOrNull()
                            val motion = mapping.partMotions[hit]?.random()
                            when {
                                motion != null -> {
                                    Log.d(TAG, "点按命中 Part=$hit → 动作 ${motion.first}#${motion.second}")
                                    // 官方 tap 语义：NORMAL 优先级，可打断 idle 打底动作，
                                    // 与正在播的同级动作不互相打断（FORCE 是强制插入用的）
                                    model.startMotion(motion.first, motion.second, MotionPriority.NORMAL)
                                }

                                mapping.expressions.isNotEmpty() -> {
                                    // 无动作模型：点哪儿切表情（部位匹配的表情优先，全局随机兜底）
                                    // 注：不走 setRandomExpression()，其 native 返回悬垂指针（UB）
                                    val exp = mapping.partExpressions[hit]?.random()
                                        ?: mapping.expressions.random()
                                    model.setExpression(exp)
                                    Log.d(TAG, "点按命中 Part=$hit → 表情 $exp")
                                    scheduleExpressionReset()
                                }

                                else -> model.startRandomMotion(null, MotionPriority.NORMAL)
                            }
                        }
                        // 注视不立即回正：保持一会（GAZE_HOLD_MS）再由 gazeResetRunnable 平滑回正
                    }
                    scheduleGazeReset()
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    scheduleGazeReset()
                    true
                }

                else -> false
            }
        }

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager?.addView(view, params)
        petView = view
        setupDone = true
        Log.i(
            TAG,
            "桌宠已启动: ${mapping.modelDir.name} 窗口 ${winW}x${winH} " +
                "(包络 ${if (fit != null) "已应用" else "缺失"})",
        )
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(gazeResetRunnable)
        mainHandler.removeCallbacks(expressionResetRunnable)
        petView?.let { v ->
            // native 资源必须排队到 GL 线程销毁；UI 线程直接 destroy 会和 GL draw 竞态 (UAF crash)
            v.releaseModel()
            try { windowManager?.removeView(v) } catch (_: Exception) {}
        }
        petView = null
        model = null
        Live2D.dispose()
        setupDone = false
        isRunning = false
        super.onDestroy()
    }

    /**
     * 表情展示几秒后淡回默认脸：fadeOutExpression 启动一个空表情走官方交叉淡化。
     * （不能用 resetExpression：那是 StopAllMotions 硬停，表情 1 帧跳没，还会激励物理振铃。）
     * 重复点击会重置计时，展示最新表情。
     */
    private val expressionResetRunnable = Runnable {
        expressionResetPending = false
        petView?.queueModel { m -> m.fadeOutExpression() }
    }

    private fun scheduleExpressionReset() {
        mainHandler.removeCallbacks(expressionResetRunnable)
        expressionResetPending = true
        mainHandler.postDelayed(expressionResetRunnable, EXPRESSION_HOLD_MS)
    }

    /**
     * 注视保持：松手后角色继续看着最后的触点，GAZE_HOLD_MS 后回正到看正前，
     * CubismTargetPoint 限速追踪自动平滑过渡（"看一眼再移开"的效果）。
     * 重复触摸会重置计时。
     *
     * 回正必须用 dragScene(0,0)（场景坐标 (0,0)=正前）。
     * 不能用 drag(0f,0f)：那是窗口像素语义，(0,0)=注视窗口左上角像素点，
     * 角色会永久停在望左上（AngleX-30°/AngleY+30°/眼球满幅）——之前就是这么写坏的。
     */
    private val gazeResetRunnable = Runnable {
        petView?.queueModel { m -> m.dragScene(0f, 0f) }
    }

    private fun scheduleGazeReset() {
        mainHandler.removeCallbacks(gazeResetRunnable)
        mainHandler.postDelayed(gazeResetRunnable, GAZE_HOLD_MS)
    }

    /**
     * 随机反应：语音唤醒命中时由 VoiceWakeService 通过 [ACTION_REACT] 触发。
     *
     * 动作/表情各半（用户要求"随机执行一个动作或者表情"）；只有一类就用那一类；
     * 都没有则什么都不做（发声方那边还有震动兜底）。
     *
     * 随机池用 mapping.allMotions / mapping.expressions（与点按处理同源）。
     * **不要用 model.setRandomExpression()**：其 native 返回悬垂指针（UB）。
     */
    private fun performRandomReaction() {
        val map = mapping ?: return
        val motions = map.allMotions
        val expressions = map.expressions
        val playMotion = when {
            motions.isNotEmpty() && expressions.isNotEmpty() -> Random.nextBoolean()
            motions.isNotEmpty() -> true
            expressions.isNotEmpty() -> false
            else -> {
                Log.d(TAG, "语音唤醒：模型无动作也无表情，忽略")
                return
            }
        }
        if (playMotion) {
            val (group, no) = motions.random()
            Log.d(TAG, "语音唤醒 → 动作 $group#$no")
            petView?.queueModel { it.startMotion(group, no, MotionPriority.NORMAL) }
        } else {
            val expression = expressions.random()
            Log.d(TAG, "语音唤醒 → 表情 $expression")
            petView?.queueModel { it.setExpression(expression) }
            scheduleExpressionReset()
        }
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "桌面宠物", NotificationManager.IMPORTANCE_MIN),
        )
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, PetService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_myplaces)
            .setContentTitle("MineAvata 桌宠运行中")
            .setContentIntent(contentIntent)
            .addAction(0, "关闭", stopIntent)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    companion object {
        private const val TAG = "PetService"
        private const val PREFS_NAME = "pet_prefs"
        private const val PREF_MODEL = "model"
        private const val PREF_SIZE = "size"
        private const val EXTRA_SIZE = "size_ratio"
        private const val EXTRA_MODEL = "model"
        private const val ACTION_STOP = "com.example.mineavata.action.PET_STOP"

        /** 语音唤醒命中：播一个随机动作或表情（VoiceWakeService 发送，就地消费不重启） */
        const val ACTION_REACT = "com.example.mineavata.action.PET_REACT"
        private const val CHANNEL_ID = "pet_service"
        private const val NOTIF_ID = 2
        private const val DEFAULT_SIZE_RATIO = 0.45f
        private const val MOVE_THRESHOLD = 12f
        private const val EXPRESSION_HOLD_MS = 3_000L
        private const val GAZE_HOLD_MS = 2_000L

        @Volatile
        var isRunning: Boolean = false
            private set

        /** 调节大小：保存后通过服务重启生效 */
        fun setPetSize(context: Context, ratio: Float) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putFloat(PREF_SIZE, ratio).apply()
            // 带 extra 启动：运行中会走 onStartCommand 的"参数变化→重启"路径；未运行则直接按新尺寸启动
            val i = Intent(context, PetService::class.java)
                .putExtra(EXTRA_SIZE, ratio)
            if (isRunning) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
                else context.startService(i)
            } else {
                context.startForegroundService(i)
            }
        }

        fun getSize(context: Context): Float =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getFloat(PREF_SIZE, DEFAULT_SIZE_RATIO)

        fun start(context: Context, modelName: String? = null) {
            val i = Intent(context, PetService::class.java)
            modelName?.let { i.putExtra(EXTRA_MODEL, it) }
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PetService::class.java))
        }
    }
}
