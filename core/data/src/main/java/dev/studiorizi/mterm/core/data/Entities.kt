package dev.studiorizi.mterm.core.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val mode: String,
    val cwd: String?,
    val commandJson: String,
    val createdAt: Long,
)

@Entity(tableName = "rootfs")
data class RootfsEntity(
    @PrimaryKey val id: String,
    val version: String,
    val state: String,
    val updatedAt: Long,
)

@Entity(tableName = "mounts")
data class MountEntity(
    @PrimaryKey val mountId: String,
    val safUri: String,
    val mirrorPath: String,
)
