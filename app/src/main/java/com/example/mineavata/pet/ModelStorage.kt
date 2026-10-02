package com.example.mineavata.pet

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.StatFs
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.Charset
import java.util.zip.ZipFile

/**
 * 模型存储管理：
 * - 内置模型（assets/live2d/）首次启动释放到 filesDir/live2d/
 * - 用户导入模型（zip → 自动解压解析）也存到 filesDir/live2d/
 * - 扫描 = 列 filesDir/live2d/ 下含 model3.json 的目录
 */
object ModelStorage {

    private const val TAG = "ModelStorage"
    private const val PREFS_NAME = "storage_prefs"
    private const val PREF_BUILTINS = "builtin_dirs"
    private const val PREF_REMOVED_BUILTINS = "removed_builtins"
    private const val PREF_TEXTURE_LIMIT = "texture_limit"

    /** 内置资产结构/版本变化时 +1，会强制重新释放并清理旧版内置 */
    private const val BUILTIN_VERSION = 4

    /** 首次迁移时需要清理的历史内置（不再随包携带） */
    private const val LEGACY_REMOVED_BUILTIN = "qiangwei"

    fun modelsRoot(context: Context): File = File(context.filesDir, "live2d")

    /**
     * 释放 assets 内置模型：
     * - 递归拷贝（支持纹理子目录）
     * - 标记文件记录 [BUILTIN_VERSION]，版本变化强制重释放
     * - 已从内置下架的目录自动清理（仅限内置释放过的目录，不碰用户导入）
     */
    fun ensureBuiltinModels(context: Context) {
        val rootOut = modelsRoot(context)
        rootOut.mkdirs()
        val assetDirs = try {
            context.assets.list("live2d")?.toList() ?: return
        } catch (_: Exception) {
            return
        }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val firstMigration = !prefs.contains(PREF_BUILTINS)
        val previous = prefs.getStringSet(PREF_BUILTINS, emptySet()) ?: emptySet()
        val tombstones = prefs.getStringSet(PREF_REMOVED_BUILTINS, emptySet()) ?: emptySet()

        for (dir in assetDirs) {
            // 用户主动删过的内置不重建（墓碑机制）
            if (dir in tombstones) continue
            val outDir = File(rootOut, dir)
            // 标记里带上当前细节程度：用户改了上限就重新释放，让贴图按新上限压缩
            val markerVal = "$BUILTIN_VERSION:${getTextureLimit(context)}"
            val marker = File(outDir, ".ready")
            if (marker.exists() && marker.readText().trim() == markerVal) continue
            outDir.deleteRecursively()
            outDir.mkdirs()
            copyAssetDir(context, "live2d/$dir", outDir)
            applyTextureLimit(outDir, getTextureLimit(context))
            marker.writeText(markerVal)
            Log.i(TAG, "内置模型已释放: $dir (细节程度=${getTextureLimit(context)})")
        }

        // 清理：旧内置目录集中不再携带的（蔷薇免费版等），只动内置清单记录过的目录
        val removed = (previous + if (firstMigration) setOf(LEGACY_REMOVED_BUILTIN) else emptySet())
            .filter { it !in assetDirs.toSet() }
        removed.forEach { name ->
            if (File(rootOut, name).deleteRecursively()) {
                Log.i(TAG, "内置模型已下架并清理: $name")
            }
        }
        prefs.edit().putStringSet(PREF_BUILTINS, assetDirs.toSet()).apply()
    }

    /** 递归拷贝 assets 目录到 filesDir（区分文件/目录） */
    private fun copyAssetDir(context: Context, assetPath: String, out: File) {
        val children: List<String> = try {
            context.assets.list(assetPath)?.toList().orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
        if (children.isEmpty()) {
            // 叶子 = 文件
            try {
                context.assets.open(assetPath).use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "assets 拷贝失败: $assetPath", e)
            }
            return
        }
        out.mkdirs()
        for (name in children) {
            copyAssetDir(context, "$assetPath/$name", File(out, name))
        }
    }

    /** 扫描全部可用模型（目录名列表） */
    fun scan(context: Context): List<String> {
        val root = modelsRoot(context)
        if (!root.exists()) return emptyList()
        return root.listFiles()
            ?.filter { it.isDirectory && it.listFiles()?.any { f -> f.name.endsWith(".model3.json") } == true }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()
    }

    // ---------- 导入流水线（由 ImportManager 的后台协程调用；失败一律抛 ImportException 带可读原因） ----------

    /** 带用户可读原因的导入失败（格式/编码/空间/校验问题都走它，UI 直接展示 message） */
    class ImportException(message: String) : Exception(message)

    /** [inspectZip] 的判定结果：用哪个编码解压 + 文件数/未压缩总字节（进度显示用） */
    data class ZipPlan(val charset: Charset, val fileCount: Int, val totalBytes: Long)

    /**
     * 压缩包预检（对已拷入私有目录的 zip 文件操作）：
     * 1. 魔数体检（RAR/非 zip 直接判死，不浪费解压时间）
     * 2. 磁盘空间预检（可用 < zip×3 不启动，避免解压到一半没空间）
     * 3. 条目名编码预判：ZipFile 随机访问只读中央目录 + model3.json 文本，
     *    比较 UTF-8 / GBK 谁能把条目名全解出来且模型引用能对上，胜者只解压一遍。
     *    （旧逻辑是"流式解压→引用对不上→全删换编码重来"，大包白跑一轮）
     */
    fun inspectZip(zip: File, context: Context): ZipPlan {
        val head = ByteArray(8)
        val n = zip.inputStream().use { it.read(head) }
        if (n < 4) throw ImportException("压缩包为空或读取失败")
        val isRar = head[0] == 'R'.code.toByte() && head[1] == 'a'.code.toByte() &&
            head[2] == 'r'.code.toByte() && head[3] == '!'.code.toByte()
        if (isRar) throw ImportException("检测到 RAR 压缩包，请先转成 zip 再导入")
        val isZip = head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
        if (!isZip) throw ImportException("不是有效的 zip 压缩包")

        val free = StatFs(context.filesDir.path).availableBytes
        val need = zip.length() * 3
        if (free < need) throw ImportException("存储空间不足：需要约 ${mb(need)}，仅剩 ${mb(free)}")

        val candidates = buildList {
            add(Charsets.UTF_8)
            runCatching { charset("GBK") }.getOrNull()?.let { add(it) }
        }
        val scores = candidates.map { cs -> cs to scoreZip(zip, cs) }
        // 优选"名字全解得出且引用对得上"；其次"名字全解得出"；都不行按 UTF-8 走，让 validateStaging 报错
        val chosen = scores.firstOrNull { !it.second.malformed && it.second.refsOk != false }?.first
            ?: scores.firstOrNull { !it.second.malformed }?.first
            ?: Charsets.UTF_8
        val s = scores.firstOrNull { it.first == chosen }?.second
        val plan = ZipPlan(chosen, s?.fileCount ?: 0, s?.totalBytes ?: 0L)
        Log.i(TAG, "压缩包检查通过: ${mb(zip.length())}，${plan.fileCount} 个文件，条目编码 ${chosen.name()}")
        return plan
    }

    private fun mb(bytes: Long): String = String.format("%.1fMB", bytes / 1048576f)

    private class ZipScore(
        /** 存在该编码解不出的条目名（UTF-8 这类严格解码器遇到非法字节会抛） */
        val malformed: Boolean,
        /** model3.json 引用与条目名是否对得上；null = 无声明可比对（裸模型） */
        val refsOk: Boolean?,
        val fileCount: Int,
        val totalBytes: Long,
    )

    /** 用给定编码开包打分：只读中央目录和 model3.json 文本，不解压任何文件内容，毫秒级 */
    private fun scoreZip(zip: File, cs: Charset): ZipScore {
        var malformed = false
        var fileCount = 0
        var totalBytes = 0L
        val names = HashSet<String>()
        var jsonEntryName: String? = null
        var jsonText: String? = null
        try {
            ZipFile(zip, cs).use { zf ->
                val en = zf.entries()
                while (en.hasMoreElements()) {
                    // UTF-8 等严格编码解码非法字节时，取条目这一步抛 IllegalArgumentException
                    val e = try { en.nextElement() } catch (_: IllegalArgumentException) { malformed = true; break }
                    val name = e.name.replace('\\', '/')
                    if (e.isDirectory) continue
                    fileCount++
                    if (e.size > 0) totalBytes += e.size
                    names.add(name)
                    if (jsonEntryName == null && name.endsWith(".model3.json")) {
                        jsonEntryName = name
                        jsonText = runCatching { zf.getInputStream(e).reader(Charsets.UTF_8).readText() }.getOrNull()
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "scoreZip(${cs.name()}) 失败", e)
            return ZipScore(true, null, 0, 0)
        }
        val refsOk = jsonText?.let { checkRefsAgainstEntries(it, jsonEntryName!!, names) }
        return ZipScore(malformed, refsOk, fileCount, totalBytes)
    }

    /** model3.json 的关键引用（Moc/Physics/DisplayInfo/Textures）对照 zip 条目名（相对 json 所在目录） */
    private fun checkRefsAgainstEntries(jsonText: String, jsonEntryName: String, names: Set<String>): Boolean? {
        return try {
            val fileRefs = JSONObject(jsonText).optJSONObject("FileReferences") ?: return null
            val prefix = jsonEntryName.substringBeforeLast('/', "")
            val dir = if (prefix.isEmpty()) "" else "$prefix/"
            fun ok(rel: String): Boolean = names.contains(dir + rel.replace('\\', '/'))
            var hasRefs = false
            fileRefs.optString("Moc").takeIf { it.isNotEmpty() }?.let {
                hasRefs = true; if (!ok(it)) return false
            }
            fileRefs.optString("Physics").takeIf { it.isNotEmpty() }?.let {
                hasRefs = true; if (!ok(it)) return false
            }
            fileRefs.optString("DisplayInfo").takeIf { it.isNotEmpty() }?.let {
                hasRefs = true; if (!ok(it)) return false
            }
            fileRefs.optJSONArray("Textures")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val t = arr.optString(i)
                    if (t.isNotEmpty()) {
                        hasRefs = true
                        if (!ok(t)) return false
                    }
                }
            }
            if (hasRefs) true else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 从本地落盘的 zip 按 [plan] 解压（ZipFile 随机访问，单遍，不再有编码重试）。
     * [onProgress] 每个 64KB 块回调一次（已解字节/总字节/文件序号/文件数/当前文件名），
     * 调用方（ImportManager）在回调里做 ensureActive 协作取消 + 节流发射进度状态。
     * @throws ImportException 条目名全部损坏 / 压缩包为空
     */
    fun extractFromFile(
        zip: File,
        plan: ZipPlan,
        outDir: File,
        onProgress: (bytes: Long, totalBytes: Long, fileIndex: Int, fileCount: Int, current: String) -> Unit,
    ) {
        var copied = 0L
        var index = 0
        try {
            ZipFile(zip, plan.charset).use { zf ->
                val entries = zf.entries().toList().filter { !it.isDirectory }
                val totalBytes = entries.sumOf { if (it.size > 0) it.size else 0L }
                val buf = ByteArray(64 * 1024)
                for (e in entries) {
                    // zip 内可能用反斜杠分隔，统一成 /
                    val relName = e.name.replace('\\', '/')
                    val outFile = File(outDir, relName).canonicalFile
                    // 防 zip 路径穿越
                    if (!outFile.path.startsWith(outDir.canonicalPath)) {
                        Log.w(TAG, "zip 条目可疑，跳过: ${e.name}")
                        index++
                        continue
                    }
                    onProgress(copied, totalBytes, index, entries.size, outFile.name)
                    outFile.parentFile?.mkdirs()
                    zf.getInputStream(e).use { input ->
                        outFile.outputStream().use { output ->
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                output.write(buf, 0, n)
                                copied += n
                                onProgress(copied, totalBytes, index, entries.size, outFile.name)
                            }
                        }
                    }
                    index++
                }
                onProgress(copied, totalBytes, index, entries.size, "")
            }
        } catch (e: IllegalArgumentException) {
            throw ImportException("压缩包条目名损坏（${plan.charset.name()} 无法解码）")
        }
        if (index == 0) throw ImportException("压缩包为空或条目无法解出")
    }

    /**
     * 校验 staging 解压结果并就地 sanitize（ImportManager 调用）：
     * 定位 model3.json（任意深度）→ 文件名就地 sanitize（自底向上 rename）→
     * fixupModelJson → referencesResolve。不搬运任何大文件——
     * 移交给 [installStaged] 做同分区原子 rename。
     * @return (模型目录, model3.json 文件)
     * @throws ImportException 找不到模型或引用对不上
     */
    fun validateStaging(staging: File): Pair<File, File> {
        val candidates = staging.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".model3.json") }
            .toList()
        if (candidates.isEmpty()) throw ImportException("压缩包里没有 Live2D 模型（缺 model3.json）")
        val modelDir = candidates.first().parentFile ?: throw ImportException("模型目录异常")

        // 就地 sanitize（自底向上：后代先于祖先改名，每次 rename 时快照路径仍然有效；
        // 根目录名无所谓，installStaged 会整体 rename 成 live2d/<模型名>）
        modelDir.walkBottomUp().toList().forEach { f ->
            if (f == modelDir) return@forEach
            val safe = sanitize(f.name)
            if (safe.isNotEmpty() && safe != f.name) {
                val parent = f.parentFile ?: return@forEach
                if (!f.renameTo(File(parent, safe))) Log.w(TAG, "文件名 sanitize 失败: ${f.name}")
            }
        }
        // sanitize 可能改掉 json 自己的名字，重新定位一次
        val modelJson = modelDir.listFiles()?.firstOrNull { it.name.endsWith(".model3.json") }
            ?: throw ImportException("压缩包里没有 Live2D 模型（缺 model3.json）")

        // cdi3/moc3/physics 引用改成 sanitize 后的名字（若有）
        fixupModelJson(modelDir)
        // 关键引用（Moc/纹理/物理/显示信息）必须能对上文件
        if (!referencesResolve(modelDir)) {
            throw ImportException("模型引用与文件对不上（多为压缩包条目名编码问题）")
        }
        return modelDir to modelJson
    }

    /**
     * 移入安装：校验通过的模型目录整体 rename 进 live2d/<模型名>（同分区原子，零拷贝）。
     * 模型名 = model3.json 文件名前缀（sanitize，冲突加序号），与历史行为一致。
     */
    fun installStaged(context: Context, modelDir: File, modelJson: File): String {
        var name = sanitize(modelJson.name.removeSuffix(".model3.json"))
        if (name.isBlank()) name = "model"
        val root = modelsRoot(context)
        root.mkdirs()
        val dst = File(root, uniqueName(context, name))
        if (!modelDir.renameTo(dst)) {
            // 同分区理论上不该失败；拷贝兜底，宁慢勿丢结果
            Log.w(TAG, "rename 失败，退回拷贝安装: $modelDir → $dst")
            if (!modelDir.copyRecursively(dst, overwrite = true)) {
                throw ImportException("安装模型到存储目录失败")
            }
        }
        Log.i(TAG, "模型导入成功: $dst (${dst.walkBottomUp().count { it.isFile }} 个文件)")
        return dst.name
    }

    /**
     * 校验 model3.json 的关键引用（Moc/Physics/DisplayInfo/Textures）在磁盘上都存在。
     * 动作/表情文件缺失不拦（映射层能容忍）。
     */
    private fun referencesResolve(dir: File): Boolean {
        val jsonFile = dir.walkTopDown()
            .firstOrNull { it.isFile && it.name.endsWith(".model3.json") }
            ?: return false
        return try {
            val fileRefs = JSONObject(jsonFile.readText())
                .optJSONObject("FileReferences") ?: return true // 无声明则交给 native 判断
            fun ok(rel: String): Boolean = File(dir, rel).exists()
            fileRefs.optString("Moc").takeIf { it.isNotEmpty() }?.let { if (!ok(it)) return false }
            fileRefs.optString("Physics").takeIf { it.isNotEmpty() }?.let { if (!ok(it)) return false }
            fileRefs.optString("DisplayInfo").takeIf { it.isNotEmpty() }?.let { if (!ok(it)) return false }
            fileRefs.optJSONArray("Textures")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val t = arr.optString(i)
                    if (t.isNotEmpty() && !ok(t)) return false
                }
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "model3.json 引用校验失败", e)
            false
        }
    }

    /** 删除模型目录；若是当前使用中返回 false（由调用方先切走） */
    fun delete(context: Context, name: String): Boolean {
        val dir = File(modelsRoot(context), name)
        return dir.deleteRecursively().also {
            if (it) {
                Log.i(TAG, "模型已删除: $name")
                // 删除的是内置模型时记墓碑，避免下次启动被 ensureBuiltinModels 重新释放
                val isBuiltin = try {
                    context.assets.list("live2d")?.contains(name) == true
                } catch (_: Exception) {
                    false
                }
                if (isBuiltin) {
                    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    val tombstones = prefs.getStringSet(PREF_REMOVED_BUILTINS, emptySet()).orEmpty() + name
                    prefs.edit().putStringSet(PREF_REMOVED_BUILTINS, tombstones).apply()
                }
            }
        }
    }

    fun isModelInUse(context: Context, name: String): Boolean =
        context.getSharedPreferences("pet_prefs", Context.MODE_PRIVATE)
            .getString("model", null) == name

    // ---------- 细节程度（贴图上限） ----------

    /** 当前贴图上限：2048 / 4096（默认）/ 0 = 原始尺寸 */
    fun getTextureLimit(context: Context): Int {
        val v = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(PREF_TEXTURE_LIMIT, 4096)
        return if (v == 2048 || v == 4096 || v == 0) v else 4096
    }

    /** 设置贴图上限（细节程度）：之后导入/重新释放的模型按新上限压缩 */
    fun setTextureLimit(context: Context, limit: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(PREF_TEXTURE_LIMIT, limit).apply()
    }

    /**
     * 细节程度：把 [root] 下单边超过 [limit] 的 png 贴图等比压缩到 [limit]。
     * limit 传 0（原始尺寸）不处理。
     * [onProgress]（可选）：每处理一张回调 (已完成数, 总数, 当前文件名)，
     * ImportManager 用它发射进度并做协作取消检查；不超限的 PNG 只读尺寸头，秒过。
     */
    fun applyTextureLimit(root: File, limit: Int, onProgress: ((done: Int, total: Int, name: String) -> Unit)? = null) {
        if (limit != 2048 && limit != 4096) return
        val pngs = root.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".png", ignoreCase = true) }
            .toList()
        pngs.forEachIndexed { i, f ->
            onProgress?.invoke(i, pngs.size, f.name)
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.absolutePath, bounds)
                val w = bounds.outWidth
                val h = bounds.outHeight
                if (w <= 0 || h <= 0 || (w <= limit && h <= limit)) return@forEachIndexed

                // 先用 2 的幂 inSampleSize 硬解降采样（省内存），不够再精确缩放
                var sample = 1
                while (minOf(w, h) / (sample * 2) >= limit) sample *= 2
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val decoded = BitmapFactory.decodeFile(f.absolutePath, opts) ?: return@forEachIndexed
                val bmp = if (decoded.width > limit || decoded.height > limit) {
                    val ratio = minOf(limit.toFloat() / decoded.width, limit.toFloat() / decoded.height)
                    Bitmap.createScaledBitmap(
                        decoded,
                        (decoded.width * ratio).toInt().coerceIn(1, limit),
                        (decoded.height * ratio).toInt().coerceIn(1, limit),
                        true,
                    )
                } else decoded
                FileOutputStream(f).use { out ->
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                Log.i(TAG, "贴图压缩: ${f.name} ${w}x${h} → ${bmp.width}x${bmp.height}")
                if (bmp !== decoded) decoded.recycle()
                bmp.recycle()
            } catch (e: Exception) {
                Log.w(TAG, "贴图压缩失败: ${f.name}", e)
            }
        }
        onProgress?.invoke(pngs.size, pngs.size, "")
    }

    /**
     * model3.json 的引用修复：
     * 安装时全部文件平铺到 dst 且可能被 sanitize 改名，这里把引用路径改写为实际文件名。
     */
    private fun fixupModelJson(dir: File) {
        val jsonFile = dir.listFiles()?.firstOrNull { it.name.endsWith(".model3.json") } ?: return
        try {
            val text = jsonFile.readText()
            // 引用形如 "subdir/yyy.moc3" 或 "yyy.moc3"。
            // 优先保持子目录结构（逐段 sanitize），只有引用仯尖到写着平铺文件时才回落为纯文件名。
            val refRegex = Regex("\"([^\"]+\\.(?:moc3|cdi3\\.json|physics3\\.json|motion3\\.json|exp3\\.json|png))\"")
            val result = refRegex.replace(text) { m ->
                val ref = m.groupValues[1]
                val segs = ref.split('/', '\\').map { sanitize(it) }
                val candidates = mutableListOf<String>()
                if (segs.size > 1) candidates += segs.joinToString("/")
                candidates += segs.last()
                candidates.firstOrNull { File(dir, it).exists() }?.let { "\"$it\"" } ?: m.value
            }
            if (result != text) jsonFile.writeText(result)
        } catch (e: Exception) {
            Log.w(TAG, "model3.json 修复失败", e)
        }
    }

    fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()

    private fun uniqueName(context: Context, base: String): String {
        val root = modelsRoot(context)
        if (!File(root, base).exists()) return base
        var i = 2
        while (File(root, "${base}_$i").exists()) i++
        return "${base}_$i"
    }
}
