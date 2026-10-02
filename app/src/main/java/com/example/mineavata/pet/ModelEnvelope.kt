package com.example.mineavata.pet

import android.util.Log
import com.arkueid.alive2d.Live2DModel
import java.io.File

/**
 * 模型「最大活动包络」：把全部动作 + 拖拽极值空跑一遍，量出宠物到底能伸展到多大。
 *
 * 为什么需要：渲染窗口是矩形，而模型画布通常是竖长条 + 四周留白。以前窗口直接开正方形，
 * 于是窗口左右两侧那两条永远不会被画到的透明区域，既能被拖动、又会吃掉本该落到桌面上的点击。
 * 拿到包络后窗口可以按包络的宽高比开，把大片空白挤出窗口。
 *
 * 顶点坐标是模型坐标系，与 scale / 视口无关，所以包络是模型的固有属性，可缓存复用。
 */
data class ModelEnvelope(
    val minX: Float,
    val minY: Float,
    val maxX: Float,
    val maxY: Float,
    /** 模型坐标系下的画布尺寸；换算投影时要用 */
    val canvasW: Float,
    val canvasH: Float,
) {
    val width: Float get() = maxX - minX
    val height: Float get() = maxY - minY
    val centerX: Float get() = (minX + maxX) * 0.5f
    val centerY: Float get() = (minY + maxY) * 0.5f

    val valid: Boolean
        get() = width > 0f && height > 0f && canvasW > 0f && canvasH > 0f

    /**
     * 求解窗口尺寸与模型摆放参数，使包络正好填满窗口（四周留 [padding] 比例的余量）。
     *
     * 窗口宽高比 = 包络宽高比 → 窗口就是「宠物能到达的范围」，多余透明区最小。
     * 窗口高度沿用调用方给的尺寸，语义是「宠物的最大伸展高度」。
     *
     * 投影换算镜像自 MatrixManager::GetMvp
     * （app/libs/alive2d/alive2d/src/main/cpp/Live2D/Main/src/MatrixManager.cpp）：
     * 它按窗口形状二选一分支，两个分支下「像素 / 模型单位」的系数不同，必须跟着选，
     * 否则宠物会溢出窗口被裁掉。改那边记得同步改这里。
     */
    fun solveWindow(preferredHeight: Int, padding: Float = 0.03f): WindowFit {
        val height = preferredHeight.coerceAtLeast(1)
        val width = (height * (this.width / this.height)).toInt().coerceAtLeast(1)

        // MatrixManager 的分支条件：_mw > 1.0f && _ww < _wh → 按画布宽铺满
        val fitByWidth = canvasW > 1.0f && width < height
        val keep = 1f - padding

        // 贴合维度上让包络占 keep 比例
        val scale = if (fitByWidth) keep * canvasW / this.width else keep * canvasH / this.height

        // 包络中心挪到窗口中心。offset 的单位是场景坐标而非像素，
        // 且换算系数随分支变（见上面注释），所以除数要跟着分支走。
        val divisor = if (fitByWidth) canvasW else canvasH
        val offsetX = -2f * centerX * scale / divisor
        val offsetY = -2f * centerY * scale / divisor

        return WindowFit(width, height, scale, offsetX, offsetY)
    }

    data class WindowFit(
        val width: Int,
        val height: Int,
        val scale: Float,
        val offsetX: Float,
        val offsetY: Float,
    )

    companion object {
        private const val TAG = "ModelEnvelope"
        private const val CACHE_NAME = ".envelope"
        private const val CACHE_VERSION = 1

        /** 采样步长（秒）。动作是平滑的，0.1 足够抓住包络 */
        private const val DT = 0.1f

        /** 单个动作的采样步数上限，防止循环动作无限跑 */
        private const val MAX_STEPS = 60

        /** 四周外扩比例，抵消动作交接处没采样到的物理摆动 */
        private const val MARGIN = 0.08f

        /**
         * 取包络：优先用缓存，没有就扫一遍并落盘。
         *
         * [model] 必须已经 loadModelJson、且还没开始渲染 —— 扫描会重置动作与参数状态。
         */
        fun obtain(modelDir: File, model: Live2DModel): ModelEnvelope? {
            loadCached(modelDir)?.let {
                Log.i(TAG, "包络缓存命中: ${modelDir.name} ${it.width}x${it.height}")
                return it
            }
            val scanned = scan(model)
            if (scanned == null) {
                Log.w(TAG, "包络扫描失败: ${modelDir.name}")
                return null
            }
            Log.i(
                TAG,
                "包络已算出: ${modelDir.name} ${scanned.width}x${scanned.height} " +
                    "画布 ${scanned.canvasW}x${scanned.canvasH}",
            )
            saveCache(modelDir, scanned)
            return scanned
        }

        /** 对一个已加载的模型实例求包络（纯 CPU，不碰 GL） */
        private fun scan(model: Live2DModel): ModelEnvelope? {
            val raw = model.scanMotionEnvelope(DT, MAX_STEPS, MARGIN) ?: return null
            if (raw.size < 6) return null
            return ModelEnvelope(raw[0], raw[1], raw[2], raw[3], raw[4], raw[5])
                .takeIf { it.valid }
        }

        /** 读缓存；没有、过期或损坏都返回 null */
        fun loadCached(modelDir: File): ModelEnvelope? {
            val cache = File(modelDir, CACHE_NAME)
            if (!cache.exists()) return null
            // 模型文件改过就作废（moc3/动作/物理都可能变）
            val modelJson = modelDir.listFiles()
                ?.firstOrNull { it.name.endsWith(".model3.json") }
                ?: return null
            if (cache.lastModified() < modelJson.lastModified()) return null

            return try {
                val lines = cache.readText().trim().lines()
                if (lines.size < 2 || lines[0].trim().toIntOrNull() != CACHE_VERSION) return null
                val v = lines[1].trim().split(Regex("\\s+")).map { it.toFloat() }
                if (v.size < 6) return null
                ModelEnvelope(v[0], v[1], v[2], v[3], v[4], v[5]).takeIf { it.valid }
            } catch (e: Exception) {
                Log.w(TAG, "包络缓存损坏，忽略: ${cache.name}", e)
                null
            }
        }

        private fun saveCache(modelDir: File, env: ModelEnvelope) {
            try {
                File(modelDir, CACHE_NAME).writeText(
                    "$CACHE_VERSION\n" +
                        "${env.minX} ${env.minY} ${env.maxX} ${env.maxY} " +
                        "${env.canvasW} ${env.canvasH}\n",
                )
            } catch (e: Exception) {
                Log.w(TAG, "包络缓存写入失败: ${modelDir.name}", e)
            }
        }
    }
}