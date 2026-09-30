package org.bestdroid.tts

/** JNI entry points into libbestdroid.so (OpenBST engine, tables embedded). */
object NativeBst {
    init {
        System.loadLibrary("bestdroid")
    }

    /** "1995,1998ENG,..." — the builds the library carries. */
    @JvmStatic external fun nativeBuildsCsv(): String

    /**
     * Synthesizes one chunk. [text] is already encoded for the build
     * (Latin bytes, or the build's legacy code page) and NUL-free.
     * Returns null when the engine has nothing to say.
     */
    @JvmStatic external fun nativeSay(
        build: String, text: ByteArray, pitch: Int, rate: Int
    ): ShortArray?

    /** Native sample rate of a build (e.g. 11025, 10000, 10800). */
    @JvmStatic external fun nativeRate(build: String): Int

    /** Engine default for a voice parameter ("pitch", "top", "level", "voice", "rate"). */
    @JvmStatic external fun nativeDefaultParam(build: String, param: String): Int

    fun builds(): List<String> =
        nativeBuildsCsv().split(",").map { it.trim() }.filter { it.isNotEmpty() }
}
