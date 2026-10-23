package com.hoshino.wenku8reader.data.local.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 持久化整本导出任务，应用下次启动时可恢复未完成任务。 */
@Entity(tableName = "download_tasks")
data class DownloadTaskEntity(
    @PrimaryKey val bookId: Int,
    val bookName: String,
    val format: String,
    val encoding: String,
    val progress: Float,
    val status: String,
    val filePath: String?,
    val error: String?,
)
