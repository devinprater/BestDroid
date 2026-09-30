package org.bestdroid.tts

/**
 * What each engine build needs in order to speak.
 *
 * Ported from iBestSpeech Shared/VoiceCatalog.swift. The engine predates
 * Unicode: several builds read text as bytes in a legacy single-byte code
 * page. Which builds those are is measured, not assumed:
 * - 2006RUS reads CP1251; Latin returns samples then all silence.
 * - 2006ARA reads CP1256 (also reads Latin); its own page is preferred.
 * - 2006GRE reads CP1253 (also reads Latin); its own page is preferred.
 * - 2006HEB reads Latin — Hebrew phonetics in Latin letters ("ze mivchan").
 */
data class VoiceInfo(
    val build: String,
    /** BCP-47 tag, used for the Android Voice registration. */
    val language: String,
    /** Java charset name this build reads, or null when it reads Latin. */
    val charset: String?,
    /** Sample text in the script this build actually reads. */
    val sample: String
)

object VoiceCatalog {
    const val ENGLISH_BUILD = "2006ENG"

    /** Every build the engine carries, newest generation first within each group. */
    val all: List<VoiceInfo> = listOf(
        // 1995
        VoiceInfo("1995", "en-US", null, "Hello, this is the Keynote Gold voice."),
        // 1998 modules
        VoiceInfo("1998ENG", "en-US", null, "Hello, this is the Keynote Gold voice."),
        VoiceInfo("1998DUT", "nl-NL", null, "Dit is een test van de spraaksynthese."),
        VoiceInfo("1998FRN", "fr-FR", null, "Ceci est un test de la synthese vocale."),
        VoiceInfo("1998GRM", "de-DE", null, "Dies ist ein Test der Sprachsynthese."),
        VoiceInfo("1998ITL", "it-IT", null, "Questo e un test della sintesi vocale."),
        VoiceInfo("1998SPN", "es-ES", null, "Esto es una prueba de sintesis de voz."),
        // 2006 builds
        VoiceInfo("2006ARA", "ar-SA", "windows-1256", "مرحبا، هذا اختبار للصوت."),
        VoiceInfo("2006DUT", "nl-NL", null, "Dit is een test van de spraaksynthese."),
        VoiceInfo("2006ENG", "en-US", null, "Hello, this is the Keynote Gold voice."),
        VoiceInfo("2006FRE", "fr-FR", null, "Ceci est un test de la synthese vocale."),
        VoiceInfo("2006GER", "de-DE", null, "Dies ist ein Test der Sprachsynthese."),
        VoiceInfo("2006GRE", "el-GR", "windows-1253", "Γεια σου, αυτό είναι ένα τεστ."),
        VoiceInfo("2006HEB", "he-IL", null, "shalom, ze mivchan."),
        VoiceInfo("2006ITA", "it-IT", null, "Questo e un test della sintesi vocale."),
        VoiceInfo("2006JPN", "ja-JP", null, "kore wa tesuto desu."),
        VoiceInfo("2006POL", "pl-PL", null, "To jest test syntezy mowy."),
        VoiceInfo("2006POR", "pt-PT", null, "Isto e um teste de sintese de voz."),
        VoiceInfo("2006RUS", "ru-RU", "windows-1251", "Привет, это тест речи."),
        VoiceInfo("2006SPA", "es-ES", null, "Esto es una prueba de sintesis de voz.")
    )

    fun infoFor(build: String): VoiceInfo? = all.firstOrNull { it.build == build }

    fun languageFor(build: String): String = infoFor(build)?.language ?: "en-US"

    fun sampleFor(build: String): String =
        infoFor(build)?.sample ?: "Hello, this is the Keynote Gold voice."

    /**
     * Encodes text into the build's code page. Null when the page has no
     * mapping for some character — the signal to fall back to English
     * rather than feed the engine bytes it will read as silence.
     */
    fun encodeForBuild(text: String, info: VoiceInfo): ByteArray? {
        // Latin-reading builds get UTF-8, exactly as the iOS port hands the
        // engine a String: for Latin text UTF-8 *is* the single-byte page.
        val cs = info.charset ?: return text.toByteArray(Charsets.UTF_8)
        // Non-Latin path: use the encoder strictly; unmappable chars fail.
        return try {
            val charset = java.nio.charset.Charset.forName(cs)
            if (!charset.newEncoder().canEncode(text)) return null
            text.toByteArray(charset)
        } catch (e: Exception) {
            null
        }
    }

    /** What to call a build in the interface: generation + language name. */
    fun displayName(build: String): String {
        val generation = when {
            build.startsWith("1995") -> "Keynote Gold 1995"
            build.startsWith("1998") -> "Keynote Gold 1998"
            build.startsWith("2006") -> "Keynote Gold 2006"
            else -> "Keynote Gold"
        }
        val info = infoFor(build) ?: return generation
        val langName = try {
            java.util.Locale.forLanguageTag(info.language).displayLanguage
                .replaceFirstChar { it.uppercase() }
        } catch (e: Exception) {
            info.language
        }
        return "$generation $langName"
    }
}
