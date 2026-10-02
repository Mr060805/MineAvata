package com.example.mineavata.voice

/**
 * 唤醒词匹配：把识别出的**整句**归一化后与提示词做 contains 比对。
 *
 * 纯函数、不碰 Android API。识别引擎（[SpeechWakeDetector]）只负责产出文本，
 * 判定归这里——将来换离线 ASR 或接 LLM 整句理解，替换的是引擎，这里不动。
 */
object PhraseMatcher {

    /**
     * 归一化：去掉空白与中英标点，英文转小写。
     * 识别结果常带空格和句读（"你好，麦麦。"），用户输入的提示词也可能带，两边都要归一。
     */
    fun normalize(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            when {
                c.isWhitespace() -> Unit
                c in PUNCTUATION -> Unit
                c.isUpperCase() -> sb.append(c.lowercaseChar())
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    /**
     * 识别文本 [recognized] 里是否出现提示词 [phrase]。
     * [phrase] 归一化后为空 → 恒 false（没设词就不该命中）。
     */
    fun matches(recognized: String, phrase: String): Boolean {
        val p = normalize(phrase)
        if (p.isEmpty()) return false
        return normalize(recognized).contains(p)
    }

    /** 中文标点 + 全部 ASCII 标点（按码位区间取，避免在源码里写引号类字符引起误读） */
    private val PUNCTUATION: Set<Char> = buildSet {
        addAll(listOf('，', '。', '！', '？', '、', '；', '：', '“', '”', '‘', '’',
            '（', '）', '《', '》', '【', '】', '…', '—', '～', '·',
            '「', '」', '『', '』'))
        for (c in '!'..'/') add(c)
        for (c in ':'..'@') add(c)
        for (c in '['..'`') add(c)
        for (c in '{'..'~') add(c)
    }
}