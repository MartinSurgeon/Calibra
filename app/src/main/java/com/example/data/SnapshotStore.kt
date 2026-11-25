package com.example.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/** Stores measurement photos (camera frame + burned-in dimensions) in app-private storage. */
class SnapshotStore(context: Context) {

    private val appContext = context.applicationContext

    private val directory: File
        get() = File(appContext.filesDir, DIRECTORY).also { if (!it.exists()) it.mkdirs() }

    suspend fun save(bitmap: Bitmap): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val file = File(directory, "calibra_${System.currentTimeMillis()}.jpg")
            FileOutputStream(file).use { out ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)) { "JPEG encoding failed" }
            }
            file.absolutePath
        }
    }

    suspend fun delete(path: String) = withContext(Dispatchers.IO) {
        if (path.isBlank()) return@withContext
        runCatching {
            val file = File(path)
            if (file.parentFile?.canonicalPath == directory.canonicalPath) file.delete()
        }
    }

    /**
     * Deletes photos no saved measurement references (e.g. from deletions whose undo window
     * passed). Files younger than [minAgeMs] are kept so in-flight captures survive.
     */
    suspend fun pruneOrphans(referencedPaths: Set<String>, minAgeMs: Long = 10 * 60_000L) =
        withContext(Dispatchers.IO) {
            val cutoff = System.currentTimeMillis() - minAgeMs
            directory.listFiles()?.forEach { file ->
                if (file.absolutePath !in referencedPaths && file.lastModified() < cutoff) {
                    file.delete()
                }
            }
        }

    fun shareUri(path: String): Uri =
        FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", File(path))

    companion object {
        const val DIRECTORY = "snapshots"

        /** Decodes [path] downsampled so its longest side is at most about [maxDimension] px. */
        fun decodeSampled(path: String, maxDimension: Int): Bitmap? {
            if (path.isBlank() || !File(path).exists()) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            if (longest <= 0) return null
            var sample = 1
            while (longest / (sample * 2) >= maxDimension) sample *= 2
            return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }
}
