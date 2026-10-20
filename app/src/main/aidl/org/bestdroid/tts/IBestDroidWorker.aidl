package org.bestdroid.tts;

/**
 * The engine, in a process of its own.
 *
 * The TTS service never calls native code.  It asks this worker to
 * synthesize a chunk, then pulls the samples in bounded blocks.  Because
 * a wedged native call can only be cleared by killing the process that
 * holds it, the owner retires this worker (see shutdown) and binds a
 * replacement instead of trying to interrupt it.
 */
interface IBestDroidWorker {
    /**
     * Starts synthesizing one chunk.  Returns a request id; if the previous
     * request is still running it waits up to [waitMs] for it, then answers
     * BUSY and the owner retires this worker.  Never blocks on the engine
     * beyond that bound.
     */
    int say(String build, in byte[] text, int pitch, int rate, int waitMs);

    /**
     * The next block of little-endian 16-bit samples for [requestId], or
     * null when the request is finished, cancelled, unknown, or [waitMs]
     * passed with nothing ready.  Never returns more than [maxBytes].
     */
    byte[] pull(int requestId, int maxBytes, int waitMs);

    /** True once [requestId] has completed or the engine failed.  A cancelled
     *  request that is stuck inside native code stays false. */
    boolean finished(int requestId);

    /** Asks the synthesis thread to stop between steps.  Never interrupts it.
     *  Clearing pending audio is immediate; the request only counts as
     *  finished when the native call really returns. */
    void cancel(int requestId);

    /** Kills this worker process.  Only the owner may call this. */
    void shutdown();

    /** Native sample rate for a build, cached at startup. */
    int rate(String build);

    /** Every build this library carries, comma separated. */
    String buildsCsv();
}
