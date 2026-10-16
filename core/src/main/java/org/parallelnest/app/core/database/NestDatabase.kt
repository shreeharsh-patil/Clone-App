package org.parallelnest.app.core.database

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import org.parallelnest.app.core.CloneInfo

@Entity(tableName="instances", indices=[Index(value=["environmentKey"], unique=true), Index(value=["packageName"])])
data class CloneRecord(@PrimaryKey val id: String, val packageName: String, val name: String, val profile: Long,
    val created: Long, val lastLaunch: Long, val accent: Int, val number: Int, val engine: String,
    @ColumnInfo(defaultValue="''") val environmentKey: String, @ColumnInfo(defaultValue="-1") val virtualUser: Long, @ColumnInfo(defaultValue="'legacy'") val runtime: String,
    @ColumnInfo(defaultValue="'legacy'") val compatibility: String, @ColumnInfo(defaultValue="0") val versionCode: Long, @ColumnInfo(defaultValue="'unknown'") val abi: String) {
    fun model()=CloneInfo(id,packageName,name,profile,created,lastLaunch,accent,number,engine,virtualUser=virtualUser,runtime=runtime,compatibility=compatibility,versionCode=versionCode,abi=abi)
    companion object { fun from(r: CloneInfo)=CloneRecord(r.id,r.packageName,r.name,r.profile,r.created,r.lastLaunch,r.accent,r.number,r.engine,r.environmentKey,r.virtualUser,r.runtime,r.compatibility,r.versionCode,r.abi) }
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
@Database(entities=[CloneRecord::class,PendingCreation::class],version=2,exportSchema=true)
abstract class NestDatabase: RoomDatabase() {
    abstract fun clones(): CloneDao
    companion object {
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE instances ADD COLUMN environmentKey TEXT NOT NULL DEFAULT ''")
                db.execSQL("UPDATE instances SET environmentKey = 'work_profile:' || packageName || ':' || profile")
                db.execSQL("ALTER TABLE instances ADD COLUMN virtualUser INTEGER NOT NULL DEFAULT -1")
                db.execSQL("ALTER TABLE instances ADD COLUMN runtime TEXT NOT NULL DEFAULT 'legacy'")
                db.execSQL("ALTER TABLE instances ADD COLUMN compatibility TEXT NOT NULL DEFAULT 'legacy'")
                db.execSQL("ALTER TABLE instances ADD COLUMN versionCode INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE instances ADD COLUMN abi TEXT NOT NULL DEFAULT 'unknown'")
                db.execSQL("DROP INDEX index_instances_packageName_profile")
                db.execSQL("CREATE UNIQUE INDEX index_instances_environmentKey ON instances(environmentKey)")
                db.execSQL("CREATE INDEX index_instances_packageName ON instances(packageName)")
            }
        }
        @Volatile private var instance: NestDatabase?=null
        fun get(context: Context): NestDatabase=instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext,NestDatabase::class.java,"nest-clones.db").addMigrations(MIGRATION_1_2).build().also { instance=it }
        }
    }
}
