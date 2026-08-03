package com.hiro.codex_android.data.tts

/**
 * 流式朗读文本过滤：agent 的回答是 markdown，直接整段读代码块体验很差，
 * 因此在喂给 TTS 前把 ``` 围栏代码块整体剔除，行内 `code` 的反引号去掉但保留内容。
 * 服务端还会再做一层 markdown 语法过滤（disable_markdown_filter），这里只处理代码。
 *
 * delta 是任意切分的字符流，反引号可能跨包，末尾的连续反引号会先挂起，
 * 等到下一个非反引号字符（或 [flush]）再判定它是围栏开关还是行内代码。
 */
class SpeakTextFilter {

    private var inFence = false
    private var heldBackticks = 0

    fun feed(delta: String): String {
        val out = StringBuilder(delta.length)
        for (char in delta) {
            if (char == '`') {
                heldBackticks++
                continue
            }
            resolveBackticks()
            if (!inFence) out.append(char)
        }
        return out.toString()
    }

    /** 回答结束时调用；结尾挂起的反引号对朗读没有意义，直接丢弃。 */
    fun flush(): String {
        reset()
        return ""
    }

    fun reset() {
        inFence = false
        heldBackticks = 0
    }

    private fun resolveBackticks() {
        if (heldBackticks >= 3) inFence = !inFence
        heldBackticks = 0
    }
}
