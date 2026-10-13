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
}
