package com.example.mineavata.pet

import android.content.Context
import android.graphics.PixelFormat
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import com.arkueid.alive2d.Live2D
import com.arkueid.alive2d.Live2DModel
import com.arkueid.alive2d.MotionPriority
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.opengles.GL10
import kotlin.random.Random

/**
 * 悬浮窗用的 Live2D 渲染视图：
 * - EGL 选带 alpha 的像素格式，配合悬浮窗半透明标志实现透明背景
 * - GL 线程每帧按 alive2d 的标准流程 update + draw
 *
 * 模型与 surface 创建时序不定，用 [rendererCreated] 标记保证 createRenderer 恰好一次。
 */
class Live2DView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GLSurfaceView(context, attrs) {

    var model: Live2DModel? = null
        private set

    /** GL 线程访问；createRenderer 是否已调用 */
    private var rendererCreated = false

    /** GL 线程访问；releaseModel 后置位，防止 resize/draw 触碰已销毁的 renderer */
    @Volatile
    private var released = false

    /** GL 线程访问；动作组信息，首帧懒加载（定时随机动作用） */
    private var motionGroupsLoaded = false
    private var hasMotions = false
    private var idleGroup: String? = null

    /** GL 线程访问；动作状态机：上一帧是否有动作在播（用于捕捉"刚播完"的边沿） */
    private var wasPlayingMotion = false

    /** GL 线程访问；动作播完后渐进回正进行中 */
    private var returningToDefault = false

    /** GL 线程访问；距离上一次动作结束的站桩秒数 / 下一次自动动作的触发阈值 */
    private var idleSeconds = 0f
    private var nextAutoMotionAt = randomAutoMotionDelay()

    private var lastCt = 0L

    init {
        setEGLContextClientVersion(2)
        // 关键：请求带 alpha 通道的 EGL 配置，否则透明背景出不来
        setEGLConfigChooser { egl, display -> chooseConfig(egl, display) }
        setZOrderOnTop(true)
        // 关键：surface 格式也要带 alpha，否则悬浮窗上显示白块
        holder.setFormat(PixelFormat.TRANSLUCENT)
        setRenderer(object : Renderer {
            override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
                lastCt = System.currentTimeMillis()
                model?.let { createRendererOnce(it) }
            }

            override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
                if (released) return
                model?.resize(width, height)
            }

            override fun onDrawFrame(gl: GL10?) {
                if (released) return
                Live2D.clearBuffer() // 全透明清屏
                val m = model ?: return
                val ct = System.currentTimeMillis()
                val delta = (ct - lastCt) / 1000f
                lastCt = ct
                update(m, delta)
                m.draw()
            }
        })
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    fun setModel(m: Live2DModel) {
        // 只赋值；createRenderer 统一在 onSurfaceCreated（有 EGL context）时调，
        // 避免在 surface 就绪前于 GL 线程排队执行导致静默失败
        model = m
        // 换模型时复位动作状态机，避免继承上一个模型的计时/回正状态
        wasPlayingMotion = false
        returningToDefault = false
        idleSeconds = 0f
        nextAutoMotionAt = randomAutoMotionDelay()
    }

    fun queueModel(block: (Live2DModel) -> Unit) {
        queueEvent {
            model?.let(block)
        }
    }

    fun releaseModel() {
        released = true
        queueEvent {
            model?.destroyRenderer()
            model?.destroy()
            model = null
            Live2D.glRelease()
        }
    }

    /** 仅在 GL 线程调用 */
    private fun createRendererOnce(m: Live2DModel) {
        if (rendererCreated) return
        rendererCreated = true
        m.createRenderer(2)
    }

    /**
     * 每帧更新：loadParameters → 动作层 → saveParameters → 各叠加层 → 物理 → pose。
     *
     * 动作层是「站桩 + 定时随机动作」，不是官方 idle 接力（产品决策）：
     * 官方接力假设 Idle 组是轻微待机循环，播完零间隔续杯；而本项目导入的裸模型
     * 会把全部大动作兜底进一个组，接力会退化成 7 个大动作永不停歇连轴转。
     * 改为：平时站桩不动，每 [AUTO_MOTION_MIN_SECONDS]~[AUTO_MOTION_MAX_SECONDS] 秒
     * 随机播一个动作；播完用 [Live2DModel.updateReturnToDefault] 渐进回正到默认站姿
     * （指数逼近约 1.5s，物理输入渐变不振铃；resetAllParameters 一帧硬复位是乱晃根源，禁用）。
     */
    private fun update(m: Live2DModel, rawDelta: Float) {
        ensureMotionGroups(m)
        // 官方样例同样限制帧间隔：锁屏/后台回来的巨大 delta 会让物理子步进爆炸、fade 进度一步跳完
        val delta = rawDelta.coerceIn(0f, MAX_DELTA_SECONDS)

        var motionUpdated = false
        m.loadParameters()
        if (m.isMotionFinished) {
            if (wasPlayingMotion) {
                // 边沿：动作刚播完，参数冻结在收尾姿势（多数动作首尾不闭合）→ 启动渐进回正，
                // 同时开始站桩计时
                wasPlayingMotion = false
                returningToDefault = true
                idleSeconds = 0f
                nextAutoMotionAt = randomAutoMotionDelay()
            }
            if (returningToDefault && m.updateReturnToDefault(delta)) {
                returningToDefault = false
            }
            if (hasMotions) {
                idleSeconds += delta
                if (idleSeconds >= nextAutoMotionAt) {
                    // 定时到：等效于"自动点了一次随机动作"。IDLE 优先级最低，
                    // 用户点击触发的动作随时能打断它
                    idleSeconds = 0f
                    nextAutoMotionAt = randomAutoMotionDelay()
                    returningToDefault = false
                    m.startRandomMotion(idleGroup, MotionPriority.IDLE)
                }
            }
        } else {
            // 有动作在播（自动的或用户触发的）：停掉回正，正常驱动动作
            wasPlayingMotion = true
            returningToDefault = false
            motionUpdated = m.updateMotion(delta)
        }
        m.saveParameters()

        if (!motionUpdated) {
            m.updateBlink(delta)
        }
        m.updateBreath(delta)
        m.updateDrag(delta)
        m.updateExpression(delta)
        m.updatePhysics(delta)
        m.updatePose(delta)
    }

    /** 首帧查一次模型有哪些动作组：自动动作优先用 Idle 组，没有就随机组兜底 */
    private fun ensureMotionGroups(m: Live2DModel) {
        if (motionGroupsLoaded) return
        motionGroupsLoaded = true
        val groups = runCatching { m.motions?.keys }.getOrNull().orEmpty()
        hasMotions = groups.isNotEmpty()
        idleGroup = groups.firstOrNull { it.equals("idle", ignoreCase = true) }
    }

    private fun randomAutoMotionDelay(): Float =
        Random.nextFloat() * (AUTO_MOTION_MAX_SECONDS - AUTO_MOTION_MIN_SECONDS) + AUTO_MOTION_MIN_SECONDS

    private fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
        val attribs = intArrayOf(
            EGL10.EGL_RED_SIZE, 8,
            EGL10.EGL_GREEN_SIZE, 8,
            EGL10.EGL_BLUE_SIZE, 8,
            EGL10.EGL_ALPHA_SIZE, 8,
            EGL10.EGL_DEPTH_SIZE, 16,
            EGL10.EGL_RENDERABLE_TYPE, 4, // EGL_OPENGL_ES2_BIT
            EGL10.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        require(egl.eglChooseConfig(display, attribs, configs, 1, num) && num[0] > 0) {
            "无法获取带 alpha 的 EGL 配置"
        }
        return configs[0]!!
    }

    private companion object {
        /** 帧间隔上限（秒），与官方样例 LAppModel::Update 的 clamp 一致 */
        const val MAX_DELTA_SECONDS = 0.1f

        /** 自动随机动作的站桩间隔（秒），用户要求"约 30 秒动一下"，取 25~35 随机避免机械感 */
        const val AUTO_MOTION_MIN_SECONDS = 25f
        const val AUTO_MOTION_MAX_SECONDS = 35f
    }
}
