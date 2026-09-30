package org.bestdroid.tts

import java.util.regex.Pattern

/**
 * Turns the text a TTS client hands over into what the engine can render.
 *
 * Ported from iBestSpeech Shared/SSMLText.swift (`finish` and every helper).
 * Android clients send plain text rather than SSML, so markup is resolved
 * lightly — tags become a space (never nothing, or the words they sat
 * between join), entities decoded — and then the exact same preparation
 * runs: CLDR descriptions for unreadable characters, ASCII fold, clock-time
 * and adjacent-number repair, pronunciation dictionary, comma softening.
 */
object TextPipeline {

    // ------------------------------------------------------------------
    // Entry point
    // ------------------------------------------------------------------

    /** Plain TTS text -> engine-ready text for the voice's language. */
    fun prepare(raw: String, language: String = "en-US"): String {
        var text = stripTags(decodeEntities(raw))
        text = describeUnsupportedCharacters(text, language)
        text = foldForEngine(text)
        text = collapseWhitespace(text)
        text = closeGapsBeforePunctuation(text)
        text = fixTimes(text)
        text = expandInteriorDots(text)
        text = expandIpAddresses(text)
        text = applyPronunciations(text)
        text = expandCapsWithLowercaseTail(text)
        text = separateAdjacentNumbers(text)
        text = softenCommas(text)
        return text.trim()
    }

    // ------------------------------------------------------------------
    // Markup (light: Android sends text, not SSML)
    // ------------------------------------------------------------------

    private val commentPattern = Pattern.compile("<!--[\\s\\S]*?-->")

    fun stripTags(input: String): String {
        var text = commentPattern.matcher(input).replaceAll(" ")
        // <sub alias="...">enclosed</sub> speaks its alias.
        text = replaceAll(text, "<sub\\s+alias\\s*=\\s*[\"']([^\"']*)[\"'][^>]*>[^<]*</sub>") { g -> g[1] }
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '<') {
                val end = text.indexOf('>', i)
                if (end < 0) break // unterminated tag: drop the remainder
                out.append(' ')
                i = end + 1
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    // ------------------------------------------------------------------
    // Clock times: digit-flanked colon -> hyphen + space
    // ------------------------------------------------------------------

    /** "5:19 PM" reads as "five nineteen PM"; a raw colon is silence. */
    fun fixTimes(text: String): String {
        val chars = text.toCharArray()
        val out = StringBuilder(chars.size + 8)
        for (index in chars.indices) {
            val flanked = index > 0 && index + 1 < chars.size &&
                chars[index - 1].isDigit() && chars[index + 1].isDigit()
            if (chars[index] == ':' && flanked) out.append("- ") else out.append(chars[index])
        }
        return out.toString()
    }

    // ------------------------------------------------------------------
    // Folding
    // ------------------------------------------------------------------

    private val typographicReplacements = mapOf(
        '‘' to "'", '’' to "'", '‚' to ",", '‛' to "'",
        '“' to "\"", '”' to "\"", '„' to "\"", '‟' to "\"",
        '–' to "-", '—' to "-", '―' to "-", '…' to "...",
        '′' to "'", '″' to "\""
    )

    private val invisibleChars = setOf(
        '­', '​', '‌', '‍', '‎', '‏',
        '‪', '‫', '‬', '‭', '‮', '⁠',
        '⁦', '⁧', '⁨', '⁩', '﻿'
    )

    fun foldForEngine(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val ch = String(Character.toChars(cp))
            when {
                ch.length == 1 && invisibleChars.contains(ch[0]) -> { /* removed */ }
                cp == 0x00A0 -> out.append(' ')
                // The engine's own lead-in: obeyed, not read. A space, not a
                // deletion, so words either side cannot join.
                cp == '~'.code -> out.append(' ')
                cp < 128 -> out.appendCodePoint(cp)
                cp in 0xFF01..0xFF5E -> out.appendCodePoint(cp - 0xFEE0)
                ch.length == 1 && typographicReplacements.containsKey(ch[0]) ->
                    out.append(typographicReplacements[ch[0]])
                else -> {
                    val folded = CldrText.stripDiacritics(ch)
                    if (folded == ch) {
                        out.append(ch) // no ASCII form: passed through
                    } else {
                        folded.forEach { c -> if (c.code < 128) out.append(c) }
                    }
                }
            }
            i += Character.charCount(cp)
        }
        return out.toString()
    }

    // ------------------------------------------------------------------
    // Characters the engine cannot read -> CLDR descriptions
    // ------------------------------------------------------------------

    fun describeUnsupportedCharacters(text: String, language: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            var unit = String(Character.toChars(cp))
            var next = i + Character.charCount(cp)
            // Group trailing variation selectors with their base, as one
            // grapheme: CLDR keys the description on the base alone.
            while (next < text.length) {
                val ncp = text.codePointAt(next)
                if (!CldrText.isVariationSelector(ncp)) break
                unit += String(Character.toChars(ncp))
                next += Character.charCount(ncp)
            }
            when {
                cp < 128 -> out.append(unit)
                unit.length == 1 && typographicReplacements.containsKey(unit[0]) -> out.append(unit)
                unit.length == 1 && invisibleChars.contains(unit[0]) -> out.append(unit)
                else -> {
                    val single = String(Character.toChars(cp))
                    val folded = CldrText.stripDiacritics(single)
                    if (folded != single && folded.any { it.code < 128 }) {
                        out.append(unit) // foldable: the fold owns it
                    } else {
                        val unitName = unitNameFor(unit, language, out)
                        if (unitName != null) {
                            out.append(' ').append(unitName).append(' ')
                        } else {
                            val desc = descriptionFor(unit, language)
                            if (desc == null) out.append(unit)
                            else out.append(' ').append(desc).append(' ')
                        }
                    }
                }
            }
            i = next
        }
        return out.toString()
    }

    private fun descriptionFor(unit: String, language: String): String? {
        CldrText.descriptionFor(unit, language)?.let { return it }
        // Strip variation selectors and retry on the base.
        val baseBuilder = StringBuilder()
        var bi = 0
        while (bi < unit.length) {
            val cp = unit.codePointAt(bi)
            if (!CldrText.isVariationSelector(cp)) baseBuilder.appendCodePoint(cp)
            bi += Character.charCount(cp)
        }
        val base = baseBuilder.toString()
        if (base != unit && base.isNotEmpty()) {
            CldrText.descriptionFor(base, language)?.let { return it }
        }
        // A sequence with no whole entry: ask about its first scalar.
        val first = String(Character.toChars(unit.codePointAt(0)))
        if (first != unit) return CldrText.descriptionFor(first, language)
        return null
    }

    private fun unitNameFor(unit: String, language: String, precededBy: CharSequence): String? {
        val key = CldrText.scalarKeyFor(unit) ?: return null
        val number = numberImmediatelyBefore(precededBy) ?: return null
        val forms = UnitPlurals.names[language.substringBefore('-')]?.get(key) ?: return null
        val category = pluralCategoryOf(number.first, number.second, language)
        return forms[category] ?: forms["other"]
    }

    private fun numberImmediatelyBefore(text: CharSequence): Pair<Int, Boolean>? {
        val digits = StringBuilder()
        var fractional = false
        var seenDigit = false
        var i = text.length - 1
        while (i >= 0) {
            val c = text[i]
            when {
                c.isDigit() -> { digits.insert(0, c); seenDigit = true; i-- }
                c == '.' && seenDigit && !fractional -> { fractional = true; i-- }
                else -> break
            }
        }
        if (!seenDigit) return null
        // A decimal point belongs to the number only between digits.
        if (fractional && (i < 0 || !text[i].isDigit())) fractional = true
        val value = digits.toString().toIntOrNull() ?: return null
        return value to fractional
    }

    fun pluralCategoryOf(value: Int, fractional: Boolean, language: String): String {
        val n = kotlin.math.abs(value)
        if (fractional) return "other"
        return when (language.substringBefore('-')) {
            "fr" -> if (n == 0 || n == 1) "one" else "other"
            "ja" -> "other"
            "pl" -> {
                if (n == 1) "one"
                else {
                    val tens = n % 10; val hundreds = n % 100
                    if (tens in 2..4 && hundreds !in 12..14) "few"
                    else if (n != 1 && (tens in 0..1 || tens in 5..9 || hundreds in 12..14)) "many"
                    else "other"
                }
            }
            "ru" -> {
                val tens = n % 10; val hundreds = n % 100
                if (tens == 1 && hundreds != 11) "one"
                else if (tens in 2..4 && hundreds !in 12..14) "few"
                else if (tens == 0 || tens in 5..9 || hundreds in 11..14) "many"
                else "other"
            }
            "ar" -> {
                val hundreds = n % 100
                when {
                    n == 0 -> "zero"; n == 1 -> "one"; n == 2 -> "two"
                    hundreds in 3..10 -> "few"; hundreds in 11..99 -> "many"
                    else -> "other"
                }
            }
            "he" -> when {
                n == 1 -> "one"; n == 2 -> "two"
                n != 0 && n % 10 == 0 -> "many"
                else -> "other"
            }
            else -> if (n == 1) "one" else "other"
        }
    }

    // ------------------------------------------------------------------
    // Pronunciations (case-sensitive, whole-word)
    // ------------------------------------------------------------------

    private val compiledPronunciations: List<Triple<String, Pattern, String>> by lazy {
        Pronunciations.ordered.sortedByDescending { it.first.length }.mapNotNull { (term, replacement) ->
            try {
                Triple(term, Pattern.compile("\\b${Pattern.quote(term)}\\b"), replacement)
            } catch (e: Exception) {
                null
            }
        }
    }

    fun applyPronunciations(text: String): String {
        var result = text
        for ((term, regex, replacement) in compiledPronunciations) {
            if (!result.contains(term)) continue
            result = regex.matcher(result).replaceAll(java.util.regex.Matcher.quoteReplacement(replacement))
        }
        return result
    }

    // ------------------------------------------------------------------
    // Caps run + lowercase tail ("UIs" -> "U eyes")
    // ------------------------------------------------------------------

    private val capsTailPattern = Pattern.compile("\\b([A-Z]{2,})([a-z]+)\\b")

    fun expandCapsWithLowercaseTail(text: String): String {
        return replaceAll(text, "\\b([A-Z]{2,})([a-z]+)\\b") { g ->
            val run = g[1]; val tail = g[2]
            if (tail == "s" && run.endsWith("I")) {
                val head = run.dropLast(1)
                val spelled = head.toCharArray().joinToString(" ") { it.toString() }
                if (spelled.isEmpty()) "eyes" else "$spelled eyes"
            } else {
                val spelled = run.toCharArray().joinToString(" ") { it.toString() }
                "$spelled ${if (tail == "s") "z" else tail}"
            }
        }
    }

    // ------------------------------------------------------------------
    // IPv4 ("192.168.0.199" -> "192 dot 168 dot 0 dot 199")
    // ------------------------------------------------------------------

    private val ipPattern = Pattern.compile("\\b(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\b")

    fun expandIpAddresses(text: String): String =
        replaceAll(text, "\\b(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\b") { g ->
            "${g[1]} dot ${g[2]} dot ${g[3]} dot ${g[4]}"
        }

    // ------------------------------------------------------------------
    // Interior dots ("claude.ai" at an edge -> "claude dot a i")
    // ------------------------------------------------------------------

    fun expandInteriorDots(text: String): String {
        val chars = text.toCharArray()
        val out = StringBuilder(text.length + 8)
        for (index in chars.indices) {
            if (chars[index] != '.' || index == 0 || index + 1 >= chars.size ||
                !chars[index - 1].isLetter() || !chars[index + 1].isLetter()
            ) {
                out.append(chars[index]); continue
            }
            // Abbreviation chain ("e.g.", "U.S."): another dot comes first.
            var next = index + 1
            while (next < chars.size && chars[next].isLetter()) next++
            if (next < chars.size && chars[next] == '.') {
                out.append(chars[index]); continue
            }
            var back = index - 1
            while (back >= 0 && (chars[back].isLetterOrDigit())) back--
            while (back >= 0 && chars[back].isWhitespace()) back--
            var end = next
            while (end < chars.size && chars[end].isWhitespace()) end++
            var token = index - 1
            while (token >= 0 && (chars[token].isLetterOrDigit() || "./:@-_".contains(chars[token]))) token--
            val host = String(chars, token + 1, index - token - 1)
            if (back < 0 || (end >= chars.size && !host.contains("://"))) {
                out.append(" dot ")
            } else {
                out.append(chars[index])
            }
        }
        return out.toString()
    }

    // ------------------------------------------------------------------
    // Adjacent numbers ("555 1234" -> "555: 1234"; colon is load-bearing:
    // a comma ENDS the text on all thirteen 2006 builds)
    // ------------------------------------------------------------------

    fun separateAdjacentNumbers(text: String): String {
        val chars = text.toCharArray()
        val out = StringBuilder(chars.size + 8)
        for ((index, c) in chars.withIndex()) {
            if (c == ' ' && index > 0 && index + 1 < chars.size &&
                chars[index - 1].isDigit() && chars[index + 1].isDigit()
            ) {
                out.append(':')
            }
            out.append(c)
        }
        return out.toString()
    }

    /** A user-written comma becomes a colon, unless between two digits ("1,000"). */
    fun softenCommas(text: String): String {
        val chars = text.toCharArray()
        val out = StringBuilder(chars.size)
        for ((index, c) in chars.withIndex()) {
            if (c != ',') {
                out.append(c); continue
            }
            val prev = if (index > 0) chars[index - 1] else null
            val next = if (index + 1 < chars.size) chars[index + 1] else null
            if (prev != null && next != null && prev.code < 128 && next.code < 128 &&
                prev.isDigit() && next.isDigit()
            ) {
                out.append(c)
            } else {
                out.append(':')
            }
        }
        return out.toString()
    }

    fun collapseWhitespace(text: String): String =
        text.replace(Regex("\\s+"), " ")

    fun closeGapsBeforePunctuation(text: String): String =
        text.replace(Regex("\\s+([,.!?;:])"), "$1")

    // ------------------------------------------------------------------
    // Entities
    // ------------------------------------------------------------------

    private val namedEntities = listOf(
        "&nbsp;" to " ", "&ensp;" to " ", "&emsp;" to " ", "&thinsp;" to " ",
        "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&apos;" to "'",
        "&ldquo;" to "\"", "&rdquo;" to "\"", "&lsquo;" to "'",
        "&rsquo;" to "'", "&mdash;" to "-", "&ndash;" to "-",
        "&hellip;" to "...", "&amp;" to "&" // last: "&amp;lt;" must not become "<"
    )
    private val hexEntity = Pattern.compile("&#[xX]([0-9A-Fa-f]+);")
    private val decEntity = Pattern.compile("&#([0-9]+);")

    fun decodeEntities(input: String): String {
        var text = replaceAll(input, "&#[xX]([0-9A-Fa-f]+);") { g ->
            g[1].toIntOrNull(16)?.let { String(Character.toChars(it)) }
        }
        text = replaceAll(text, "&#([0-9]+);") { g ->
            g[1].toIntOrNull()?.let {
                try {
                    String(Character.toChars(it))
                } catch (e: Exception) {
                    null
                }
            }
        }
        for ((entity, replacement) in namedEntities) {
            text = text.replace(entity, replacement)
        }
        return text
    }

    // ------------------------------------------------------------------
    // Regex helper (group 0 = whole match)
    // ------------------------------------------------------------------

    fun replaceAll(input: String, pattern: String, transform: (List<String>) -> String?): String {
        val regex: Pattern = try {
            Pattern.compile(pattern, Pattern.DOTALL)
        } catch (e: Exception) {
            return input
        }
        val m = regex.matcher(input)
        val out = StringBuilder()
        var cursor = 0
        while (m.find()) {
            out.append(input, cursor, m.start())
            val groups = (0..m.groupCount()).map { m.group(it) ?: "" }
            out.append(transform(groups) ?: m.group(0))
            cursor = m.end()
        }
        out.append(input, cursor, input.length)
        return out.toString()
    }
}
