package org.bestdroid.tts

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.DeadObjectException
import android.os.IBinder
import android.os.Looper
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The TTS service's handle on the engine worker process.
 *
 * One binding at a time.  A retired connection stays until its binder dies,
 * so a replacement cannot bind the process that is still exiting.
 */
internal object BestDroidWorkers {

    private const val TAG = "BestDroidWorkers"
    private const val BIND_TIMEOUT_S = 20L
    private const val DIE_TIMEOUT_S = 5L

    private val lock = Any()

    private class Connection(val context: Context) : ServiceConnection {
        val ready = CountDownLatch(1)
        val closed = CountDownLatch(1)
        @Volatile var api: IBestDroidWorker? = null
        @Volatile var retired = false
        var bound = false

        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            synchronized(lock) {
                if (retired) return
                api = IBestDroidWorker.Stub.asInterface(binder)
                try {
                    binder.linkToDeath({ disconnected() }, 0)
                } catch (e: DeadObjectException) {
                    disconnected()
                }
                ready.countDown()
            }
        }

        private fun unbind() {
            if (bound) {
                try {
                    context.unbindService(this)
                } catch (t: Throwable) {
                    Log.w(TAG, "unbind failed", t)
                }
                bound = false
            }
        }

        private fun disconnected() {
            synchronized(lock) {
                retired = true
                unbind()
                ready.countDown()
                closed.countDown()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) = disconnected()
        override fun onNullBinding(name: ComponentName?) = disconnected()

        override fun onBindingDied(name: ComponentName?) {
            synchronized(lock) {
                api = null
                retire()
            }
        }

        /** Called under lock.  Never waits for death while holding it. */
        fun retire() {
            if (retired) return
            retired = true
            unbind() // Drop the binding BEFORE asking the worker to die.
            ready.countDown()
            val target = api
            api = null
            if (target == null || !target.asBinder().isBinderAlive) {
                closed.countDown()
                return
            }
            try {
                target.shutdown()
            } catch (e: DeadObjectException) {
                closed.countDown()
            } catch (t: Throwable) {
                Log.w(TAG, "shutdown failed", t)
                closed.countDown()
            }
        }
    }

    private var connection: Connection? = null

    /** Starts a binding.  Caller holds [lock]. */
    private fun bind(ctx: Context): Connection = Connection(ctx).also { c ->
        c.bound = ctx.bindService(
            Intent(ctx, BestDroidWorkerService::class.java),
            c,
            Context.BIND_AUTO_CREATE
        )
        if (!c.bound) {
            c.ready.countDown()
            c.closed.countDown()
        }
        connection = c
    }

    /** The live worker, binding one if needed.  Never call on the main thread. */
    fun acquire(context: Context): IBestDroidWorker {
        check(Looper.myLooper() != Looper.getMainLooper()) { "worker binding on main thread" }
        val ctx = context.applicationContext
        val conn: Connection
        synchronized(lock) {
            val existing = connection
            conn = when {
                existing == null -> bind(ctx)
                existing.retired -> bind(ctx)
                existing.api != null ->
                    // A connected worker is reused if its binder is alive.
                    // If it is not, the connection is stale: replace it.
                    if (existing.api!!.asBinder().isBinderAlive) return existing.api!!
                    else { existing.retire(); bind(ctx) }
                existing.bound ->
                    // A bind is already in flight (the onCreate warm-up and
                    // the first utterance race here).  Wait on that one:
                    // retiring it would hand this caller a dead connection
                    // and send the utterance to the Google fallback.
                    existing
                else -> { existing.retire(); bind(ctx) }
            }
        }
        if (!conn.ready.await(BIND_TIMEOUT_S, TimeUnit.SECONDS)) {
            retireConnection(conn)
            throw IllegalStateException("engine worker bind timed out")
        }
        val api = conn.api
        if (conn.retired || api == null || !api.asBinder().isBinderAlive) {
            retireConnection(conn)
            throw IllegalStateException("engine worker unavailable")
        }
        return api
    }

    /** Retires the worker that served [api]; a different one is left alone. */
    fun retire(api: IBestDroidWorker?) {
        if (api == null) return
        val conn = synchronized(lock) {
            connection?.takeIf { it.api?.asBinder() === api.asBinder() }
        } ?: return
        retireConnection(conn)
    }

    private fun retireConnection(conn: Connection) {
        synchronized(lock) { conn.retire() }
        if (!conn.closed.await(DIE_TIMEOUT_S, TimeUnit.SECONDS)) {
            Log.w(TAG, "worker did not report death")
        }
        synchronized(lock) { if (connection === conn) connection = null }
    }

    /** Binds a fresh worker after retirement, for one retry. */
    fun replace(context: Context, old: IBestDroidWorker?): IBestDroidWorker {
        retire(old)
        return acquire(context)
    }
}
