package dev.studiorizi.mterm.core.data

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * MTerm Room database, version 1.
 *
 * Update policy (plan section 14.3): database migrations are NOT
 * forward-only. Any future schema change must ship a tested Migration
 * together with a downgrade/rollback path (destructive fallback only
 * after an explicit backup), so that rolling back the APK never bricks
 * the install metadata.
 */
@Database(
    entities = [SessionEntity::class, RootfsEntity::class, MountEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class MTermDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun rootfsDao(): RootfsDao
    abstract fun mountDao(): MountDao
}
