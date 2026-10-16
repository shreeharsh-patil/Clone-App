package org.parallelnest.app

import org.junit.Assert.*
import org.junit.Test
import org.parallelnest.app.core.*

class MetadataRestoreTest {
    @Test fun restoreUpdatesExistingNamesWithoutBreakingShortcuts() {
        val existing=CloneInfo("11111111-1111-1111-1111-111111111111","org.example.one","Renamed",10,1)
        val backup=existing.copy(id="22222222-2222-2222-2222-222222222222",name="Saved name",accent=3)
        val result=RegistryCodec.mergeRestored(listOf(existing),listOf(backup)).single()
        assertEquals("Saved name",result.name)
        assertEquals(existing.id,result.id)
        assertEquals(3,result.accent)
        assertEquals(1,result.number)
    }
    @Test fun restoreDoesNotDropARealAppWhenItsOldIdCollidesWithStaleMetadata() {
        val stale=CloneInfo("11111111-1111-1111-1111-111111111111","org.example.old","Old",9,1)
        val actual=stale.copy(packageName="org.example.real",profile=10)
        val result=RegistryCodec.mergeRestored(listOf(stale),listOf(actual))
        assertEquals(2,result.size)
        assertNotEquals(result[0].id,result[1].id)
        assertEquals("work_profile",result[1].engine)
    }
    @Test fun restoringVirtualPresentationNeverChangesRuntimeIdentityOrCollapsesCopies() {
        val one=CloneInfo("11111111-1111-1111-1111-111111111111","org.example.one","First",0,1,engine="virtual",virtualUser=0,runtime="ready",compatibility="probe_only",abi="bytecode")
        val two=one.copy(id="22222222-2222-2222-2222-222222222222",name="Second",profile=1,virtualUser=1,number=2)
        val restored=one.copy(name="Renamed",profile=77,virtualUser=77,versionCode=1000,runtime="failed")
        val result=RegistryCodec.mergeRestored(listOf(one,two),listOf(restored))
        assertEquals(listOf(one.copy(name="Renamed"),two),result)
    }
    @Test fun legacyAndVirtualEnvironmentNumbersDoNotCollide() {
        val legacy=CloneInfo("11111111-1111-1111-1111-111111111111","org.example.one","Legacy",0,1)
        val guest=legacy.copy(id="22222222-2222-2222-2222-222222222222",engine="virtual",virtualUser=0,runtime="ready",compatibility="probe_only",abi="bytecode")
        assertEquals(2,RegistryCodec.mergeRestored(listOf(legacy),listOf(guest)).size)
    }
}
