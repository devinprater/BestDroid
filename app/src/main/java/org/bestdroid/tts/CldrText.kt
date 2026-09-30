package org.bestdroid.tts

import android.content.Context
import java.text.Normalizer

/**
 * Character and emoji descriptions from the Unicode CLDR `tts` annotation —
 * the same source (and mirror) NVDA turns into its emoji symbol dictionaries.
 *
 * Ported from iBestSpeech Shared/CLDRText.swift. The data ships as one
 * `assets/cldr/<lang>.txt` file per language (one `HEX-KEY<TAB>description`
 * line per entry, extracted from the Swift tables); each file is decoded
 * once, lazily, on first use.
 *
 * Lookup falls back to English **per character**: a locale whose
 * descriptions are written in its own script keeps only the few ASCII
 * entries (Arabic keeps 49 of 3900), and only the gaps fill from English.
 */
object CldrText {
    @Volatile private var appContext: Context? = null
    private val tables = mutableMapOf<String, Map<String, String>>()
    private val lock = Any()

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun descriptionFor(char: String, language: String): String? {
        val base = language.substringBefore('-')
        if (base != "en") {
            table(base)?.get(char)?.let { return it }
        }
        return table("en")?.get(char)
    }

    private fun table(lang: String): Map<String, String>? {
        synchronized(lock) {
            tables[lang]?.let { return it }
            val loaded = load(lang)
            if (loaded != null) tables[lang] = loaded
            return loaded
        }
    }

    private fun load(lang: String): Map<String, String>? {
        val ctx = appContext ?: return null
        return try {
            ctx.assets.open("cldr/$lang.txt").bufferedReader(Charsets.UTF_8).use { r ->
                val out = HashMap<String, String>()
                for (line in r.lineSequence()) {
                    val tab = line.indexOf('\t')
                    if (tab < 0) continue
                    val scalars = line.substring(0, tab).split('-').mapNotNull {
                        it.toIntOrNull(16)
                    }.map { String(Character.toChars(it)) }
                    if (scalars.isEmpty()) continue
                    out[scalars.joinToString("")] = line.substring(tab + 1)
                }
                out
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Hex-scalar key a character is stored under: code points joined by "-". */
    fun scalarKeyFor(char: String): String? {
        if (char.isEmpty()) return null
        val sb = StringBuilder()
        var i = 0
        while (i < char.length) {
            val cp = char.codePointAt(i)
            if (sb.isNotEmpty()) sb.append('-')
            sb.append(cp.toString(16).uppercase())
            i += Character.charCount(cp)
        }
        return sb.toString()
    }

    fun isVariationSelector(cp: Int): Boolean =
        cp == 0xFE0E || cp == 0xFE0F || (cp in 0xE0100..0xE01EF)

    /** NFD-decompose and drop diacritics: "café" -> "cafe". */
    fun stripDiacritics(s: String): String {
        val decomposed = Normalizer.normalize(s, Normalizer.Form.NFD)
        val out = StringBuilder(decomposed.length)
        var i = 0
        while (i < decomposed.length) {
            val cp = decomposed.codePointAt(i)
            if (Character.getType(cp) != Character.NON_SPACING_MARK.toInt()) {
                out.appendCodePoint(cp)
            }
            i += Character.charCount(cp)
        }
        return out.toString()
    }
}
