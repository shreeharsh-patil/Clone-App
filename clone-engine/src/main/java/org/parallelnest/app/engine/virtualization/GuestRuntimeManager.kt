package org.parallelnest.app.engine.virtualization

import android.app.PendingIntent
import android.content.*
import kotlinx.coroutines.*
import org.parallelnest.app.core.OperationResult
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal object GuestReplies {
    data class Request(val instance: String, val result: CompletableDeferred<OperationResult>)
    val pending = ConcurrentHashMap<String, Request>()
}
class GuestResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val nonce = intent.getStringExtra("nonce") ?: return
        val pending = GuestReplies.pending[nonce] ?: return
        if (intent.getStringExtra("instance") != pending.instance) return
        pending.result.complete(OperationResult(intent.getBooleanExtra("success", false), intent.getStringExtra("message") ?: "No runtime result", pending.instance))
    }
}
internal object GuestSession { var activity: GuestHostActivity? = null }
open class GuestStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("instance") ?: return
        val result = intent.getParcelableExtra("result", PendingIntent::class.java) ?: return
        val activity = GuestSession.activity
        if (activity?.intent?.getStringExtra("instance") == id) activity.stopGuest(result)
        else result.send(context, 0, Intent().putExtra("success", true).putExtra("instance", id).putExtra("message", "Guest already stopped"))
    }
}
class GuestStop0Receiver : GuestStopReceiver()
class GuestStop1Receiver : GuestStopReceiver()

class GuestRuntimeManager(private val context: Context) {
    private suspend fun request(id: String, dispatch: (PendingIntent) -> Unit): OperationResult {
        val nonce = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<OperationResult>()
        val reply = PendingIntent.getBroadcast(context, 0, Intent(context, GuestResultReceiver::class.java).setAction("guest.$nonce").putExtra("nonce", nonce), PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_ONE_SHOT)
        GuestReplies.pending[nonce] = GuestReplies.Request(id, deferred)
        return try {
            withContext(Dispatchers.Main) { dispatch(reply) }
            withTimeout(20_000) { deferred.await() }
        } catch (_: TimeoutCancellationException) { OperationResult(false, "Guest runtime did not acknowledge execution within 20 seconds", id) }
        finally { GuestReplies.pending.remove(nonce); reply.cancel() }
    }
    suspend fun launch(id: String, user: Long): OperationResult {
        val started = android.os.SystemClock.elapsedRealtime()
        val result = request(id) { callback ->
            val slot = if(user % 2 == 0L) GuestSlot0Activity::class.java else GuestSlot1Activity::class.java
            context.startActivity(Intent(context, slot).putExtra("instance", id).putExtra("result", callback).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        android.util.Log.i("GuestRuntime", "guest_start_ms=${android.os.SystemClock.elapsedRealtime()-started} success=${result.success}")
        return result
    }
    suspend fun stop(id: String, user: Long): OperationResult = request(id) { result ->
        val receiver = if(user % 2 == 0L) GuestStop0Receiver::class.java else GuestStop1Receiver::class.java
        context.sendBroadcast(Intent(context, receiver).putExtra("instance", id).putExtra("result", result))
    }
}
