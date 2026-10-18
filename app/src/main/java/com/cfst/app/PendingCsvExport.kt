package com.cfst.app

import android.app.Application
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/** Private, write-once picker snapshots. Only the small random ID enters saved state. */
internal class PendingCsvExport(private val application: Application) {
    private val directory = File(application.filesDir, "pending-csv")
    private fun file(id: String?): File {
        if (id == null || !id.matches(Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"))) {
            throw IOException("待导出 CSV 快照缺失，请重新导出")
        }
        return File(directory, "$id.csv")
    }

    suspend fun prepare(snapshot: String): String = withContext(Dispatchers.IO) {
        cleanup(null)
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("无法创建 CSV 快照目录")
        val id = UUID.randomUUID().toString()
        val target = file(id)
        try {
            if (!target.createNewFile()) throw IOException("CSV 快照标识冲突")
            java.io.FileOutputStream(target).use {
                it.write(snapshot.toByteArray(Charsets.UTF_8))
                it.fd.sync()
            }
            cleanup(id)
            id
        } catch (e: Exception) {
            target.delete()
            throw e
        }
    }

    suspend fun deliver(id: String?, uri: Uri) = withContext(Dispatchers.IO) {
        val source = file(id)
        // Validate/open the frozen source BEFORE opening (and possibly truncating) destination.
        if (!source.isFile || source.length() == 0L) throw IOException("待导出 CSV 快照缺失，请重新导出")
        source.inputStream().use { input ->
            val output = application.contentResolver.openOutputStream(uri)
                ?: throw IOException("无法打开 CSV 输出流")
            output.use { input.copyTo(it); it.flush() }
        }
    }

    fun discard(id: String?) {
        if (id != null) runCatching { file(id).delete() }
    }

    /** At most eight retained snapshots, 24h expiry; an outstanding picker is exempt. */
    fun cleanup(activeId: String?) {
        val active = runCatching { file(activeId) }.getOrNull()
        val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        val remaining = directory.listFiles().orEmpty().filter { candidate ->
            if (candidate == active) false
            else if (candidate.lastModified() < cutoff) { candidate.delete(); false }
            else true
        }.sortedByDescending { it.lastModified() }
        remaining.drop(if (active?.exists() == true) 7 else 8).forEach { it.delete() }
    }
}
