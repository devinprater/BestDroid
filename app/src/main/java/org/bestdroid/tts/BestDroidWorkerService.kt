package org.bestdroid.tts

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import java.util.concurrent.Executors

/**
 * Holds the native engine, in its own process.
 *
 * One request at a time, on one thread: the engine is not re-entrant.
 * Cancellation only flips a flag; a native call that will not return is
 * cleared by the owner killing this process, never by interrupting the
 * thread that is inside it.
 */
class BestDroidWorkerService : Service() {

    companion object {
        private const val TAG = "BestDroidWorker"
        /** say() answers this when the previous request is still running. */
        const val BUSY = -2
        /** Debug-only text prefix that makes the engine hang, to prove recovery. */
        const val HANG_PREFIX = "\$HANG"
    }

    private val synthesis = Executors.newSingleThreadExecutor { r ->
        Thread(r, "bestdroid-engine").apply { priority = Thread.NORM_PRIORITY + 1 }
    }

    // Guarded by state.  pull() waits on it; the synthesis thread notifies it.
    private val state = Object()
    private var currentId = 0
    private var busy = false
    private var finished = true
    private var cancelled = false
    private var pending: ByteArray? = null

    @Volatile private var rates: Map<String, Int> = emptyMap()
    @Volatile private var buildsCsv: String = ""

    private val binder = object : IBestDroidWorker.Stub() {

        override fun say(build: String, text: ByteArray, pitch: Int, rate: Int, waitMs: Int): Int {
            val id: Int
            synchronized(state) {
                val deadline = System.currentTimeMillis() + waitMs.coerceIn(0, 5_000)
                while (busy) {
                    val left = deadline - System.currentTimeMillis()
                    if (left <= 0) {
                        Log.w(TAG, "say refused: request $currentId still running")
                        return BUSY
                    }
                    try {
                        (state as java.lang.Object).wait(left)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return BUSY
                    }
                }
                currentId += 1
                id = currentId
                busy = true
                finished = false
                cancelled = false
                pending = null
            }
            synthesis.submit { runRequest(id, build, text, pitch, rate) }
            return id
        }

        override fun pull(requestId: Int, maxBytes: Int, waitMs: Int): ByteArray? {
            val limit = maxBytes.coerceIn(1, 256 * 1024)
            val deadline = System.currentTimeMillis() + waitMs.coerceIn(0, 5_000)
            synchronized(state) {
                while (true) {
                    if (requestId != currentId) return null
                    if (cancelled) return null
                    val buf = pending
                    if (buf != null && buf.isNotEmpty()) {
                        val n = minOf(limit, buf.size)
                        val out = buf.copyOfRange(0, n)
                        pending = if (n == buf.size) null else buf.copyOfRange(n, buf.size)
                        return out
                    }
                    if (finished) return null
                    val left = deadline - System.currentTimeMillis()
                    if (left <= 0) return null
                    try {
                        (state as java.lang.Object).wait(left)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return null
                    }
                }
            }
        }

        override fun finished(requestId: Int): Boolean = synchronized(state) {
            requestId == currentId && finished
        }

        override fun cancel(requestId: Int) {
            synchronized(state) {
                if (requestId == currentId) {
                    // Stop delivering audio now.  The request is NOT finished
                    // until the native call actually returns, so the owner can
                    // tell a prompt stop from a wedged engine.
                    cancelled = true
                    pending = null
                    (state as java.lang.Object).notifyAll()
                }
            }
        }

        override fun shutdown() {
            Log.i(TAG, "retiring worker process")
            android.os.Process.killProcess(android.os.Process.myPid())
        }

        override fun rate(build: String): Int = rates[build] ?: 0

        override fun buildsCsv(): String = buildsCsv
    }

    override fun onCreate() {
        super.onCreate()
        try {
            val builds = NativeBst.builds()
            buildsCsv = builds.joinToString(",")
            rates = builds.associateWith { b ->
                try {
                    NativeBst.nativeRate(b)
                } catch (t: Throwable) {
                    Log.e(TAG, "nativeRate failed for $b", t)
                    0
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "engine did not load", t)
        }
    }

    private fun runRequest(id: Int, build: String, text: ByteArray, pitch: Int, rate: Int) {
        val pcm = try {
            if (BuildConfig.DEBUG && text.toString(Charsets.US_ASCII).startsWith(HANG_PREFIX)) {
                Log.w(TAG, "debug hang for request $id")
                Thread.sleep(120_000)
                null
            } else {
                NativeBst.nativeSay(build, text, pitch, rate)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "nativeSay failed", t)
            null
        }
        synchronized(state) {
            if (id == currentId && !cancelled) {
                pending = pcm?.let { toBytes(it) }
            }
            busy = false
            finished = true
            (state as java.lang.Object).notifyAll()
        }
    }

    private fun toBytes(samples: ShortArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        var j = 0
        for (s in samples) {
            val v = s.toInt()
            out[j++] = (v and 0xFF).toByte()
            out[j++] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        synthesis.shutdownNow()
        super.onDestroy()
    }
}
