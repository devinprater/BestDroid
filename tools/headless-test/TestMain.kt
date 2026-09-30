import org.bestdroid.tts.EngineParameters
import org.bestdroid.tts.LanguageDetector
import org.bestdroid.tts.TextPipeline
import org.bestdroid.tts.VoiceCatalog

var failures = 0
fun check(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) {
        println("PASS $name")
    } else {
        failures++
        println("FAIL $name\n  expected: <$expected>\n  actual:   <$actual>")
    }
}

fun main() {
    // EngineParameters: neutral maps to engine nominal/default.
    check("rate neutral", EngineParameters.engineRate(100), 0)
    check("rate slowest bound", EngineParameters.engineRate(25), 300)
    check("rate fastest bound", EngineParameters.engineRate(400), -100)
    check("rate mid-slow", EngineParameters.engineRate(50), 200)
    check("rate mid-fast", EngineParameters.engineRate(250), -50)
    check("pitch neutral", EngineParameters.enginePitch(100), 80)
    check("pitch floor", EngineParameters.enginePitch(25), 50)
    check("pitch ceiling", EngineParameters.enginePitch(400), 140)

    // TextPipeline core repairs.
    check("clock time", TextPipeline.prepare("It is 5:19 PM.", "en-US").contains("5- 19"), true)
    check("adjacent numbers", TextPipeline.prepare("Room 101 202", "en-US"), "Room 101: 202")
    check("comma softened", TextPipeline.prepare("Alpha, Yesterday", "en-US"), "Alpha: Yesterday")
    check("grouped comma kept", TextPipeline.prepare("alpha 1,000 omega", "en-US"), "alpha 1,000 omega")
    check("pronunciation", TextPipeline.prepare("FaceTime call", "en-US"), "Face Time call")
    check("pronunciation AI", TextPipeline.prepare("the AI model", "en-US"), "the A I model")
    check("tilde", TextPipeline.prepare("Read ~x] hi", "en-US").contains("~"), false)
    check("nbsp", TextPipeline.prepare("a b", "en-US"), "a b")
    check("bidi stripped", TextPipeline.prepare("\u2068Read", "en-US"), "Read")
    check("curly quotes", TextPipeline.prepare("\u201chello\u201d", "en-US"), "\"hello\"")
    check("em dash", TextPipeline.prepare("a\u2014b", "en-US"), "a-b")
    check("diacritics", TextPipeline.prepare("caf\u00e9", "en-US"), "cafe")
    check("ip address", TextPipeline.prepare("ping 192.168.0.199 now", "en-US"), "ping 192 dot 168 dot 0 dot 199 now")
    check("caps tail", TextPipeline.prepare("UIs open", "en-US"), "U eyes open")
    check("fullwidth", TextPipeline.prepare("\uFF01", "en-US"), "!")
    check("entities", TextPipeline.prepare("a &amp; b &mdash; c", "en-US"), "a & b - c")
    check("tags to space", TextPipeline.prepare("iBestSpeech<br/>recently", "en-US"), "iBestSpeech recently")

    // LanguageDetector.
    check("russian script", LanguageDetector.languageOf("Привет, это тест речи."), "ru")
    check("english first", LanguageDetector.buildToSpeak("Привет, это тест речи.", current = "en-US"), "2006RUS")
    check("english no latin switch", LanguageDetector.buildToSpeak("Dies ist ein Test der Sprachsynthese und noch mehr Worte hier.", current = "en-US"), null)
    check("single word never", LanguageDetector.languageOf("Hallo"), null)
    check("german detected", LanguageDetector.languageOf("Dies ist ein Test der Sprachsynthese und noch viel mehr Text hier."), "de")

    // VoiceCatalog: 20 voices, code pages where measured.
    check("voice count", VoiceCatalog.all.size, 20)
    check("rus page", VoiceCatalog.infoFor("2006RUS")?.charset, "windows-1251")
    check("ara page", VoiceCatalog.infoFor("2006ARA")?.charset, "windows-1256")
    check("gre page", VoiceCatalog.infoFor("2006GRE")?.charset, "windows-1253")
    check("heb latin", VoiceCatalog.infoFor("2006HEB")?.charset, null)

    if (failures > 0) {
        println("$failures FAILURES")
        kotlin.system.exitProcess(1)
    }
    println("ALL PASS")
}
