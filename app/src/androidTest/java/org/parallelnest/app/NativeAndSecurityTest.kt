package org.parallelnest.app

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.parallelnest.app.core.*
import org.parallelnest.app.engine.BridgeAuth
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class NativeAndSecurityTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun nativeLibraryRejectsDuplicateEnvironmentAndRoundTrips() {
        val row=CloneInfo(UUID.randomUUID().toString(),"org.example.probe","Second",10,1)
        assertEquals(listOf(row),RegistryCodec.decode(NativeCore.canonicalize(RegistryCodec.encode(listOf(row)))))
        assertThrows(IllegalArgumentException::class.java){NativeCore.canonicalize(RegistryCodec.encode(listOf(row,row.copy(id=UUID.randomUUID().toString()))))}
    }
    @Test fun forgedBridgeCommandRejected() { assertFalse(BridgeAuth.verify(context,Intent().putExtra("op","delete").putExtra("package","org.example.probe"))) }
    @Test fun forgedBridgeReplyRejected() { assertFalse(BridgeAuth.verifyReply(context,Intent().putExtra("success",true).putExtra("profile",10L).putExtra("reply_mac",ByteArray(32)))) }
    @Test fun keystoreEncryptsAndPinVerifiesWithRetryDelay() {
        val store=SecureStore(context)
        store.put("test",byteArrayOf(1,2,3))
        assertArrayEquals(byteArrayOf(1,2,3),store.get("test"))
        store.setPin("654321".toCharArray())
        assertTrue(store.checkPin("654321".toCharArray()))
        repeat(5){assertFalse(store.checkPin("000000".toCharArray()))}
        assertFalse(store.checkPin("654321".toCharArray()))
    }
}
