package org.parallelnest.app

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.parallelnest.app.core.*
import org.parallelnest.app.core.database.*
import java.util.UUID

class DatabaseMigrationTest {
    @Test fun v1RowsAndPendingRequestsSurviveAndMultipleVirtualClonesFit() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "migration-${UUID.randomUUID()}.db"
        val legacy = CloneInfo(UUID.randomUUID().toString(), "org.example.legacy", "Existing app", 10, 123)
        context.openOrCreateDatabase(name, 0, null).use { db ->
            db.execSQL("CREATE TABLE instances (id TEXT NOT NULL PRIMARY KEY, packageName TEXT NOT NULL, name TEXT NOT NULL, profile INTEGER NOT NULL, created INTEGER NOT NULL, lastLaunch INTEGER NOT NULL, accent INTEGER NOT NULL, number INTEGER NOT NULL, engine TEXT NOT NULL)")
            db.execSQL("CREATE UNIQUE INDEX index_instances_packageName_profile ON instances(packageName,profile)")
            db.execSQL("CREATE TABLE pending_creations (nonce TEXT NOT NULL PRIMARY KEY, packageName TEXT NOT NULL, name TEXT NOT NULL, created INTEGER NOT NULL)")
            db.execSQL("INSERT INTO instances VALUES (?,?,?,?,?,?,?,?,?)", arrayOf(legacy.id,legacy.packageName,legacy.name,legacy.profile,legacy.created,0,0,1,"work_profile"))
            db.execSQL("INSERT INTO pending_creations VALUES ('pending','org.example.pending','Pending',456)")
            db.version = 1
        }
        val migrated = Room.databaseBuilder(context, NestDatabase::class.java, name).addMigrations(NestDatabase.MIGRATION_1_2).build()
        try {
            assertEquals(legacy, migrated.clones().snapshot().single().model())
            assertEquals("Pending", migrated.clones().pending("pending")?.name)
            val one = CloneInfo(UUID.randomUUID().toString(), "org.example.legacy", "Guest 1",0,1,number=1,engine="virtual",virtualUser=0,runtime="ready",compatibility="probe_only",abi="bytecode")
            val two = one.copy(id=UUID.randomUUID().toString(),profile=1,virtualUser=1,number=2)
            migrated.clones().insert(listOf(one,two).map(CloneRecord::from))
            assertEquals(3,migrated.clones().snapshot().size)
            assertEquals(legacy,migrated.clones().snapshot().single { it.id==legacy.id }.model())
        } finally { migrated.close();context.deleteDatabase(name) }
    }
}
