package com.example.mineavata.pet

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Live2D 模型自动映射器：换芯不换框的核心。
 *
 * 不硬编码任何 Part id / 动作序号，启动时对任意模型执行：
 *  1. 扫描模型目录，解析 model3.json → 动作清单、表情清单
 *  2. 解析 cdi3.json（rigger 导出的显示信息）→ 部件/参数中文名
 *  3. 关键词归类：部件 → 部位类（HEAD/ARM/PROP/TAIL/BODY/CLOTH）
 *  4. 逐动作统计其驱动的参数中文名 → 得票最多的部位类 = 动作归属
 *  5. 得到 部件id → 同类动作列表 的映射，供点按命中时查询
 *
 * 任一环节信息缺失（无 cdi3/名字是英文/驱动参数无名）都平滑退化：
 * 映射表短一截，最终点按走随机兜底，功能永不缺失。
 */
object ModelAutoMapper {

    private const val TAG = "ModelAutoMapper"

    /** 部位类 → 中文关键词（按优先级排序，先命中先得） */
    private val CLASS_KEYWORDS = linkedMapOf(
        "PROP" to listOf("旗", "抱枕", "枕头", "道具", "玩偶"),
        "TAIL" to listOf("尾"),
        "HEAD" to listOf("头", "脸", "眼", "眉", "嘴", "鼻", "耳", "发", "睫", "舌", "腮"),
        "ARM" to listOf("手", "臂", "袖", "肩", "爪"),
        "BODY" to listOf("身", "胸", "腹", "腰", "胯", "脖", "腿", "脚", "裤"),
        "CLOTH" to listOf("衣", "裙", "扣", "包", "帽", "披", "饰"),
    )

    /** 一个模型的完整映射结果 */
    class ModelMapping(
        val modelDir: File,
        val modelJsonFile: File,
        /** 动作组名 → (组内序号 → 动作文件名) */
        val motions: Map<String, List<String>>,
        /** 部件 id → 可播放的动作 (组名, 序号) 列表 */
        val partMotions: Map<String, List<Pair<String, Int>>>,
        /** 所有动作 (组名, 序号) 的全集，随机兜底用 */
        val allMotions: List<Pair<String, Int>>,
        /** 表情名称清单 */
        val expressions: List<String>,
        /** 部件 id → 表情名列表（无动作模型时的部位级退化） */
        val partExpressions: Map<String, List<String>>,
        /** 部件 id → 中文名（cdi3 原文，列表展示用） */
        val partNames: Map<String, String>,
        /** "组#序号" → 部位类（动作归属） */
        val motionClasses: Map<String, String>,
    ) {

        /** 交互能力说明（管理页列表展示用，两三行文本） */
        fun describe(): String {
            val lines = mutableListOf<String>()
            if (allMotions.isNotEmpty()) {
                val grouped = motionClasses.entries.groupBy({ it.value }, { it.key })
                val parts = grouped.entries.joinToString("、") { (cls, ms) ->
                    "${clsName(cls)}点按 ${ms.size} 个"
                }
                lines.add("动作 ${allMotions.size} 个（$parts）")
            } else if (expressions.isNotEmpty()) {
                lines.add("表情 ${expressions.size} 个：${expressions.joinToString("、") { clsNameOfExp(it) }}")
            } else {
                lines.add("无动作/表情（纯注视模型）")
            }
            val mapped = partMotions.size + partExpressions.size
            if (mapped > 0) lines.add("可点部位 $mapped 处")
            return lines.joinToString("\n")
        }

        private fun clsName(cls: String) = when (cls) {
            "HEAD" -> "头"; "ARM" -> "手"; "PROP" -> "道具"
            "TAIL" -> "尾"; "BODY" -> "身"; "CLOTH" -> "衣"
            else -> cls
        }

        private fun clsNameOfExp(name: String): String =
            classify(name)?.let { clsName(it) } ?: name
    }

    /**
     * 对 [modelDir] 下的模型做全自动解析。
     * 找不到 model3.json 返回 null；其余情况尽力而为。
     */
    fun map(context: Context, modelDir: File): ModelMapping? {
        val jsonFile = modelDir.listFiles()?.firstOrNull { it.name.endsWith(".model3.json") }
            ?: return null

        // 裸模型兜底：很多免费模型的 model3.json 不声明 Motions/Expressions，
        // 但动作/表情文件就躺在目录里。native 只认 model3.json，必须先补写声明再解析。
        patchMissingDeclarations(modelDir, jsonFile)

        val root = try {
            JSONObject(jsonFile.readText())
        } catch (e: Exception) {
            Log.w(TAG, "model3.json 解析失败", e)
            return null
        }
        val fileRefs = root.optJSONObject("FileReferences") ?: return null

        // ---- 动作清单 ----
        val motions = linkedMapOf<String, List<String>>()
        fileRefs.optJSONObject("Motions")?.let { groups ->
            groups.keys().forEach { group ->
                val arr = groups.optJSONArray(group) ?: return@forEach
                val files = (0 until arr.length())
                    .mapNotNull { arr.optJSONObject(it)?.optString("File") }
                if (files.isNotEmpty()) motions[group] = files
            }
        }

        // ---- cdi3 语义表 ----
        val partNames = mutableMapOf<String, String>()
        val paramNames = mutableMapOf<String, String>()
        fileRefs.optString("DisplayInfo").takeIf { it.isNotEmpty() }?.let { cdiName ->
            val cdiFile = File(modelDir, cdiName)
            if (cdiFile.exists()) {
                try {
                    val cdi = JSONObject(cdiFile.readText())
                    cdi.optJSONArray("Parts")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            val o = arr.optJSONObject(i) ?: continue
                            partNames[o.optString("Id")] = o.optString("Name")
                        }
                    }
                    cdi.optJSONArray("Parameters")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            val o = arr.optJSONObject(i) ?: continue
                            paramNames[o.optString("Id")] = o.optString("Name")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "cdi3.json 解析失败，退化为无语义映射", e)
                }
            }
        }

        // ---- 表情清单（无动作模型的点按退化方案）----
        val expressions = mutableListOf<String>()
        fileRefs.optJSONArray("Expressions")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("Name")
                if (name.isNotEmpty()) expressions.add(name)
            }
        }

        // ---- 动作 → 部位类：统计该动作驱动的参数中文名归属 ----
        // group 名 → (序号 → 票数最高部位类)
        val motionClasses = mutableMapOf<Pair<String, Int>, String>()
        motions.forEach { (group, files) ->
            files.forEachIndexed { idx, file ->
                val cls = classifyMotion(modelDir, file, paramNames)
                if (cls != null) motionClasses[group to idx] = cls
            }
        }

        // ---- 部件 → 部件类，再反查同类动作 ----
        val partMotions = mutableMapOf<String, MutableList<Pair<String, Int>>>()
        val allMotions = mutableListOf<Pair<String, Int>>()
        motions.forEach { (group, files) ->
            files.forEachIndexed { idx, _ ->
                allMotions.add(group to idx)
            }
        }
        partNames.forEach { (partId, name) ->
            val cls = classify(name) ?: return@forEach
            val candidates = motionClasses.filterValues { it == cls }.keys.toList()
            if (candidates.isNotEmpty()) {
                partMotions.getOrPut(partId) { mutableListOf() }.addAll(candidates)
            }
        }

        // ---- 表情归类：表情文件名本身就是语义（"害羞.exp3.json"），直接归类 ----
        // 部件无动作可播时，配同类表情
        val partExps = mutableMapOf<String, List<String>>()
        partNames.forEach { (partId, name) ->
            val cls = classify(name) ?: return@forEach
            val exps = expressions.filter { classify(it) == cls }
            if (exps.isNotEmpty()) partExps[partId] = exps
        }

        Log.i(
            TAG,
            "映射完成: ${motions.size} 个动作组 ${allMotions.size} 个动作, " +
                "${expressions.size} 个表情, " +
                "${partNames.size} 个部件中 ${partMotions.size + partExps.size} 个建立了映射",
        )
        return ModelMapping(modelDir, jsonFile, motions, partMotions, allMotions, expressions, partExps, partNames, motionClasses.mapKeys { "${it.key.first}#${it.key.second}" })
    }

    /**
     * 幂等补全：model3.json 的 FileReferences 里缺 Motions / Expressions 声明时，
     * 扫描模型目录补写（动作归入 “Auto” 组，表情 Name = 文件名去后缀）。
     * 补写成功后 native 与映射层都能看到全套动作/表情。
     */
    private fun patchMissingDeclarations(modelDir: File, jsonFile: File) {
        try {
            val root = JSONObject(jsonFile.readText())
            val fileRefs = root.optJSONObject("FileReferences") ?: return
            var dirty = false

            // ---- 动作声明缺失 → 扫 *.motion3.json ----
            val hasMotions = fileRefs.optJSONObject("Motions")
                ?.keys()?.asSequence()
                ?.any { (fileRefs.getJSONObject("Motions").optJSONArray(it)?.length() ?: 0) > 0 } == true
            if (!hasMotions) {
                val motionFiles = modelDir.walkTopDown()
                    .filter { it.isFile && it.name.endsWith(".motion3.json") }
                    .map { it.relativeTo(modelDir).invariantSeparatorsPath }
                    .sorted()
                    .toList()
                if (motionFiles.isNotEmpty()) {
                    val files = JSONArray()
                    motionFiles.forEach { files.put(
                        JSONObject()
                            .put("File", it)
                            // 带上淡入淡出时长：SDK 只认声明值，不做就是硬切闪变
                            .put("FadeInTime", 0.5)
                            .put("FadeOutTime", 0.5),
                    ) }
                    // 组名刻意不叫 "Idle"：Idle 在官方语义里是轻微待机循环，
                    // 裸模型的全部大动作兜底进 Idle 会诱导接力式连轴转。
                    // "Auto" 组由 Live2DView 的定时器按需抽取（站桩 + 25~35s 随机一个）
                    fileRefs.put("Motions", JSONObject().put("Auto", files))
                    dirty = true
                    Log.i(TAG, "补写动作声明: ${motionFiles.size} 个 → ${jsonFile.name}")
                }
            }

            // ---- 表情声明缺失 → 扫 *.exp3.json ----
            val hasExpressions = fileRefs.optJSONArray("Expressions")?.let { it.length() > 0 } == true
            if (!hasExpressions) {
                val expFiles = modelDir.walkTopDown()
                    .filter { it.isFile && it.name.endsWith(".exp3.json") }
                    .map { it.relativeTo(modelDir).invariantSeparatorsPath }
                    .sorted()
                    .toList()
                if (expFiles.isNotEmpty()) {
                    val exps = JSONArray()
                    expFiles.forEach { exp ->
                        val base = exp.substringAfterLast('/').removeSuffix(".exp3.json")
                        exps.put(JSONObject().put("Name", base).put("File", exp))
                    }
                    fileRefs.put("Expressions", exps)
                    dirty = true
                    Log.i(TAG, "补写表情声明: ${expFiles.size} 个 → ${jsonFile.name}")
                }
            }

            if (dirty) {
                root.put("FileReferences", fileRefs)
                jsonFile.writeText(root.toString(2))
            }
        } catch (e: Exception) {
            // 补写失败不致命：按原始 model3.json 继续解析
            Log.w(TAG, "补全动作/表情声明失败（按原样解析）", e)
        }
    }

    /** 动作归类：解析 motion3.json 里驱动的参数 → 中文名 → 部位类，取票王 */
    private fun classifyMotion(
        modelDir: File,
        motionFile: String,
        paramNames: Map<String, String>,
    ): String? {
        val f = File(modelDir, motionFile)
        if (!f.exists()) return null
        val votes = mutableMapOf<String, Int>()
        try {
            val d = JSONObject(f.readText())
            val curves = d.optJSONArray("Curves") ?: return null
            for (i in 0 until curves.length()) {
                val c = curves.optJSONObject(i) ?: continue
                if (c.optString("Target") != "Parameter") continue
                val cn = paramNames[c.optString("Id")] ?: continue
                val cls = classify(cn) ?: continue
                votes[cls] = (votes[cls] ?: 0) + 1
            }
        } catch (e: Exception) {
            return null
        }
        return votes.maxByOrNull { it.value }?.key
    }

    private fun classify(text: String): String? {
        for ((cls, kws) in CLASS_KEYWORDS) {
            for (kw in kws) {
                if (text.contains(kw)) return cls
            }
        }
        return null
    }

    /** 扫 assets 下所有可用模型目录 */
    fun scanAssets(context: Context): List<String> {
        val dirs = context.assets.list("live2d") ?: return emptyList()
        return dirs.filter { dir ->
            // 有 model3.json 才算有效模型
            val files = context.assets.list("live2d/$dir")
            files?.any { it.endsWith(".model3.json") } == true
        }
    }
}
