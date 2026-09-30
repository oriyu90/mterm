package dev.studiorizi.mterm.core.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SessionEntity)

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun byId(id: String): SessionEntity?

    @Query("SELECT * FROM sessions ORDER BY createdAt DESC")
    suspend fun all(): List<SessionEntity>

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface RootfsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: RootfsEntity)

    @Query("SELECT * FROM rootfs WHERE id = :id")
    suspend fun byId(id: String): RootfsEntity?

    @Query("SELECT * FROM rootfs")
    suspend fun all(): List<RootfsEntity>

    @Query("DELETE FROM rootfs WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface MountDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: MountEntity)

    @Query("SELECT * FROM mounts WHERE mountId = :mountId")
    suspend fun byId(mountId: String): MountEntity?

    @Query("SELECT * FROM mounts")
    suspend fun all(): List<MountEntity>

    @Query("DELETE FROM mounts WHERE mountId = :mountId")
    suspend fun delete(mountId: String)
}
