package org.parallelnest.app.core.database

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import org.parallelnest.app.core.CloneInfo

@Entity(tableName="instances", indices=[Index(value=["packageName","profile"], unique=true)])
data class CloneRecord(@PrimaryKey val id: String, val packageName: String, val name: String, val profile: Long,
    val created: Long, val lastLaunch: Long, val accent: Int, val number: Int, val engine: String) {
    fun model()=CloneInfo(id,packageName,name,profile,created,lastLaunch,accent,number,engine)
    companion object { fun from(r: CloneInfo)=CloneRecord(r.id,r.packageName,r.name,r.profile,r.created,r.lastLaunch,r.accent,r.number,r.engine) }
}
@Entity(tableName="pending_creations")
data class PendingCreation(@PrimaryKey val nonce: String, val packageName: String, val name: String, val created: Long)
@Dao interface CloneDao {
    @Query("SELECT * FROM instances ORDER BY created, id") fun observe(): Flow<List<CloneRecord>>
    @Query("SELECT * FROM instances ORDER BY created, id") suspend fun snapshot(): List<CloneRecord>
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun insert(rows: List<CloneRecord>)
    @Query("DELETE FROM instances") suspend fun clear()
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun remember(row: PendingCreation)
    @Query("SELECT * FROM pending_creations WHERE nonce=:nonce") suspend fun pending(nonce: String): PendingCreation?
    @Query("SELECT * FROM pending_creations ORDER BY created") suspend fun pendingAll(): List<PendingCreation>
    @Query("DELETE FROM pending_creations WHERE nonce=:nonce") suspend fun forget(nonce: String)
}
@Database(entities=[CloneRecord::class,PendingCreation::class],version=1,exportSchema=true)
abstract class NestDatabase: RoomDatabase() {
    abstract fun clones(): CloneDao
    companion object {
        @Volatile private var instance: NestDatabase?=null
        fun get(context: Context): NestDatabase=instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext,NestDatabase::class.java,"nest-clones.db").build().also { instance=it }
        }
    }
}
