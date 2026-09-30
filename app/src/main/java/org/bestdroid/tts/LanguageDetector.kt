package org.bestdroid.tts

/**
 * Works out which language a piece of text is in, so a voice can be picked
 * for it rather than for the system setting.
 *
 * Ported from iBestSpeech Shared/LanguageDetector.swift: script first
 * (Cyrillic, Greek, Arabic, Hebrew, kana each belong to exactly one build —
 * a single such character settles it when it dominates), then function
 * words for the eight Latin-script languages, requiring both a floor and a
 * clear win. Returning null is a real answer: a one-word text never moves
 * a voice, and an English voice never switches on Latin-script text (any
 * such detection is a guess about shared words, and a wrong switch changes
 * the voice the user hears, which is worse than no switch).
 */
object LanguageDetector {

    /** Languages the engine carries, and the build to use for each. */
    val preferredBuilds: Map<String, String> = mapOf(
        "en" to "2006ENG", "nl" to "2006DUT", "fr" to "2006FRE",
        "de" to "2006GER", "it" to "2006ITA", "es" to "2006SPA",
        "pt" to "2006POR", "pl" to "2006POL", "ru" to "2006RUS",
        "ar" to "2006ARA", "he" to "2006HEB", "el" to "2006GRE",
        "ja" to "2006JPN"
    )

    private val latinLanguages = setOf("en", "de", "fr", "es", "it", "nl", "pt", "pl")

    private enum class Script { LATIN, CYRILLIC, GREEK, ARABIC, HEBREW, KANA, OTHER }

    private val uniqueScripts = listOf(
        Script.CYRILLIC to "ru", Script.GREEK to "el",
        Script.ARABIC to "ar", Script.HEBREW to "he"
    )

    /** The build to speak [text] with, or null to keep the requested voice. */
    fun buildToSpeak(text: String, current: String): String? {
        val detected = languageOf(text) ?: return null
        val currentBase = base(current)
        if (detected == currentBase) return null
        if (currentBase == "en" && detected in latinLanguages) return null
        return preferredBuilds[detected]
    }

    fun languageOf(text: String): String? {
        val counts = scriptCounts(text)
        val letters = counts.values.sum()
        if (letters == 0) return null

        for ((script, language) in uniqueScripts) {
            val count = counts[script] ?: 0
            if (count > 0 && count * 2 >= letters) return language
        }
        if ((counts[Script.KANA] ?: 0) > 0) return "ja"

        // Latin, and only Latin: mixed-script text is no job for a word list.
        if (counts.size > 1 && counts[Script.LATIN] == null) return null
        val latin = counts[Script.LATIN] ?: return null
        if (latin * 2 < letters) return null
        return latinLanguageOf(text)
    }

    private fun scriptOf(cp: Int): Script? {
        if (!Character.isLetter(cp)) return null
        return when (cp) {
            in 0x0041..0x024F, in 0x1E00..0x1EFF,
            in 0x2C60..0x2C7F, in 0xA720..0xA7FF -> Script.LATIN
            in 0x0400..0x04FF, in 0x0500..0x052F,
            in 0x2DE0..0x2DFF, in 0xA640..0xA69F -> Script.CYRILLIC
            in 0x0370..0x03FF, in 0x1F00..0x1FFF -> Script.GREEK
            in 0x0600..0x06FF, in 0x0750..0x077F, in 0x08A0..0x08FF,
            in 0xFB50..0xFDFF, in 0xFE70..0xFEFF -> Script.ARABIC
            in 0x0590..0x05FF, in 0xFB1D..0xFB4F -> Script.HEBREW
            in 0x3040..0x309F, in 0x30A0..0x30FF, in 0x31F0..0x31FF,
            in 0x3400..0x4DBF, in 0x4E00..0x9FFF, in 0xF900..0xFAFF,
            in 0xFF66..0xFF9D -> Script.KANA
            else -> Script.OTHER
        }
    }

    private fun scriptCounts(text: String): Map<Script, Int> {
        val counts = mutableMapOf<Script, Int>()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            scriptOf(cp)?.let { counts[it] = (counts[it] ?: 0) + 1 }
            i += Character.charCount(cp)
        }
        return counts
    }

    private fun latinLanguageOf(text: String): String? {
        val all = text.lowercase().split(Regex("[^\\p{L}]+")).filter { it.isNotEmpty() }
        if (all.isEmpty()) return null

        // A word in exactly one list settles it — stops "No Updates
        // Available" reading as Spanish on the strength of "no".
        for ((language, list) in DetectorWords.exclusiveWords) {
            if (all.any { it in list }) return language
        }

        val requiredHits = if (all.size < 3) 2 else 1
        val scores = DetectorWords.functionWords.map { (language, list) ->
            val hits = all.count { it in list }
            Triple(language, hits.toDouble() / all.size, hits)
        }.sortedByDescending { it.second }

        val best = scores.firstOrNull() ?: return null
        if (best.third < requiredHits || best.second < 0.08) return null
        // A tie between two languages is no evidence for either.
        if (scores.size > 1 && scores[1].third == best.third) return null
        // One hit in a long text is a coincidence, not a detection.
        val decisive = best.third >= 2 || best.second >= 0.5
        if (!decisive) return null
        return best.first
    }

    fun base(language: String): String = language.substringBefore('-')
}
