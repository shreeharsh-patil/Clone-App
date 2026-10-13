package org.parallelnest.app.engine

import android.content.Context
import android.content.Intent
import org.parallelnest.app.core.SecureStore
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray

object BridgeAuth {
    private fun payload(intent: Intent): ByteArray = listOf("op", "nonce", "time", "package", "name").joinToString("\u0000") { intent.getStringExtra(it).orEmpty() }.plus("\u0000" + intent.getStringArrayListExtra("paths").orEmpty().joinToString("\u0000")).toByteArray(Charsets.UTF_8)
    fun signature(key: ByteArray, bytes: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(bytes)
    fun sign(context: Context, intent: Intent) {
        val key = SecureStore(context).get("bridge") ?: error("Profile must be provisioned from this installation")
        intent.putExtra("mac", signature(key, payload(intent)))
    }
    private fun replyPayload(intent: Intent): ByteArray = JSONArray().put(intent.getStringExtra("nonce")).put(intent.getBooleanExtra("success",false)).put(intent.getStringExtra("message")).put(intent.getLongExtra("profile",-1))
        .put(JSONArray(intent.getStringArrayListExtra("packages").orEmpty())).put(JSONArray(intent.getStringArrayListExtra("labels").orEmpty())).put(intent.getStringExtra("diagnostics_json")).toString().toByteArray(Charsets.UTF_8)
    fun signReply(context: Context, intent: Intent, nonce: String) {
        intent.putExtra("nonce",nonce)
        val key=SecureStore(context).get("bridge") ?: error("Missing provisioned reply key")
        intent.putExtra("reply_mac",signature(key,replyPayload(intent)))
    }
    fun verifyReply(context: Context, intent: Intent): Boolean = runCatching {
        val key=SecureStore(context).get("bridge") ?: return false
        val mac=intent.getByteArrayExtra("reply_mac") ?: return false
        MessageDigest.isEqual(mac,signature(key,replyPayload(intent)))
    }.getOrDefault(false)
    @Synchronized fun verify(context: Context, intent: Intent): Boolean {
        val key = SecureStore(context).get("bridge") ?: return false
        val mac = intent.getByteArrayExtra("mac") ?: return false
        val time = intent.getStringExtra("time")?.toLongOrNull() ?: return false
        val now = System.currentTimeMillis()
        if (time < now - 120_000 || time > now + 10_000 || !MessageDigest.isEqual(mac, signature(key, payload(intent)))) return false
        val nonce = intent.getStringExtra("nonce") ?: return false
        if (nonce.length != 36) return false
        val prefs = context.getSharedPreferences("used-commands", Context.MODE_PRIVATE)
        if (prefs.contains(nonce)) return false
        val edit = prefs.edit()
        prefs.all.filterValues { it is Long && it < now - 120_000 }.keys.forEach { edit.remove(it) }
        return edit.putLong(nonce, now).commit()
    }
}
