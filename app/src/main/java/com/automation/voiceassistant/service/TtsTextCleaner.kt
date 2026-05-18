package com.automation.voiceassistant.service

object TtsTextCleaner {

    /**
     * Strips characters that degrade TTS quality:
     * - Markdown formatting (bold, italic, headers, fenced code blocks, lists)
     * - Inline code backticks → keeps the text inside, removes the backticks
     * - URLs
     * - Symbols that get spelled out awkwardly (#, *, _, ~, `, [, ], etc.)
     *
     * Keeps: letters (including accented/ñ), digits, spaces, and punctuation
     * that influences speech rhythm (. , ; : ! ? ' … )
     */
    fun clean(input: String): String = input
        // Fenced code blocks  ```…```  — drop the whole block (code isn't speakable)
        .replace(Regex("```[\\s\\S]*?```"), " ")
        // Inline code  `palabra`  — keep the word inside, strip the backticks
        .replace(Regex("`([^`\n]*)`"), "$1")
        // Bold/italic markers  *** ** * ___ __ _
        .replace(Regex("\\*{1,3}|_{1,3}"), "")
        // Markdown headers  # ## ###  (remove only the # prefix, keep the title text)
        .replace(Regex("^#{1,6}\\s*", RegexOption.MULTILINE), "")
        // Markdown unordered list bullets  - item / * item  (remove bullet, keep text)
        .replace(Regex("^[\\-\\*]\\s+", RegexOption.MULTILINE), "")
        // Markdown horizontal rules  --- or ***  (drop entirely)
        .replace(Regex("^[-\\*]{3,}\\s*$", RegexOption.MULTILINE), " ")
        // URLs  http:// https://
        .replace(Regex("https?://\\S+"), " ")
        // Lone backticks that survived the inline-code step
        .replace(Regex("`"), "")
        // Symbols with no spoken value
        .replace(Regex("[#~\\[\\]{}|\\\\@\$%^&+=<>()\"]"), "")
        // Normalize whitespace (multiple spaces / newlines → single space)
        .replace(Regex("\\s+"), " ")
        .trim()
}

