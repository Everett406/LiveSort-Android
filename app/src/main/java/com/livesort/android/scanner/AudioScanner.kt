package com.livesort.android.scanner

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.media.MediaMetadataRetriever
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import com.livesort.android.model.Song

/**
 * 音频文件扫描器
 *
 * 支持两种方式：
 * 1. MediaStore 扫描：按文件夹分组列出设备所有音频
 * 2. SAF 文件夹扫描：用户选择特定文件夹后递归扫描
 */
object AudioScanner {

    private val AUDIO_EXTENSIONS = setOf("mp3", "wav", "flac", "m4a", "aac", "ogg", "wma")

    /**
     * 从 MediaStore 获取所有包含音频文件的文件夹
     */
    fun getAudioFolders(context: Context): List<AudioFolder> {
        val folders = mutableMapOf<String, MutableList<AudioFile>>()
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

        val projection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.BUCKET_DISPLAY_NAME,
                MediaStore.Audio.Media.RELATIVE_PATH,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.DATA
            )
        } else {
            arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.DATA
            )
        }

        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sortOrder = "${MediaStore.Audio.Media.BUCKET_DISPLAY_NAME} ASC, ${MediaStore.Audio.Media.TITLE} ASC"

        context.contentResolver.query(uri, projection, selection, null, sortOrder)?.use { cursor ->
            while (cursor.moveToNext()) {
                val file = cursor.toAudioFile(context)
                val folderName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val idx = cursor.getColumnIndex(MediaStore.Audio.Media.BUCKET_DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) ?: "Unknown" else "Unknown"
                } else {
                    val idx = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                    val path = if (idx >= 0) cursor.getString(idx) ?: "" else ""
                    java.io.File(path).parentFile?.name ?: "Unknown"
                }
                folders.getOrPut(folderName) { mutableListOf() }.add(file)
            }
        }

        return folders.map { (name, files) ->
            AudioFolder(
                name = name,
                fileCount = files.size,
                files = files.sortedBy { it.title }
            )
        }.sortedBy { it.name }
    }

    /**
     * 通过 SAF 扫描用户选择的文件夹
     */
    fun scanFolderWithSAF(context: Context, treeUri: Uri): List<AudioFile> {
        val documentFile = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        val files = mutableListOf<AudioFile>()
        scanDocumentRecursive(context, documentFile, files)
        return files.sortedBy { it.title }
    }

    private fun scanDocumentRecursive(context: Context, doc: DocumentFile, out: MutableList<AudioFile>) {
        if (!doc.isDirectory) {
            val ext = doc.name?.substringAfterLast('.', "")?.lowercase() ?: return
            if (ext in AUDIO_EXTENSIONS) {
                val coverPath = extractCoverFromUri(context, doc.uri)
                out.add(
                    AudioFile(
                        uri = doc.uri,
                        displayName = doc.name ?: "Unknown",
                        title = doc.name?.substringBeforeLast('.', "") ?: "Unknown",
                        artist = "",
                        album = "",
                        duration = 0,
                        size = doc.length(),
                        coverPath = coverPath
                    )
                )
            }
            return
        }
        for (child in doc.listFiles()) {
            scanDocumentRecursive(context, child, out)
        }
    }

    /**
     * 将 AudioFile 转换为 Song（用于导入到 ViewModel）
     */
    fun AudioFile.toSong(id: Int): Song {
        return Song(
            id = id,
            filename = this.uri.toString(),
            title = this.title,
            artist = this.artist,
            album = this.album,
            durationSec = (this.duration / 1000.0),
            fileSizeBytes = this.size,
            coverPath = this.coverPath
        )
    }

    /**
     * 对于 content URI 的文件，复制到 app 私有缓存目录并返回真实路径
     */
    fun resolveToLocalPath(context: Context, uri: Uri): String? {
        return try {
            // 如果已经是 file:// 直接返回
            if (uri.scheme == "file") return uri.path

            // 尝试从 MediaStore 获取路径
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                val projection = arrayOf(MediaStore.Audio.Media.DATA)
                context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val path = cursor.getString(0)
                        if (!path.isNullOrEmpty()) return path
                    }
                }
            }

            // 复制到私有缓存
            val fileName = getFileNameFromUri(context, uri) ?: "temp_audio"
            val cacheFile = java.io.File(context.cacheDir, "audio_import/$fileName")
            cacheFile.parentFile?.mkdirs()
            context.contentResolver.openInputStream(uri)?.use { input ->
                cacheFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            cacheFile.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun getFileNameFromUri(context: Context, uri: Uri): String? {
        var name: String? = null
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    name = cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                }
            }
        }
        if (name == null) {
            name = uri.path?.let { java.io.File(it).name }
        }
        return name
    }

    /**
     * 从音频文件中提取内嵌封面并保存到缓存目录
     */
    fun extractCoverFromUri(context: Context, uri: Uri): String? {
        return try {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
            } catch (e: Exception) {
                // Fallback: try file descriptor for SAF URIs
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    retriever.setDataSource(pfd.fileDescriptor)
                } ?: throw e
            }
            val art = retriever.embeddedPicture
            retriever.release()
            if (art != null) {
                val coverFile = java.io.File(context.cacheDir, "covers/${uri.hashCode()}.jpg")
                coverFile.parentFile?.mkdirs()
                coverFile.writeBytes(art)
                coverFile.absolutePath
            } else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun Cursor.toAudioFile(context: Context): AudioFile {
        val id = getLong(getColumnIndexOrThrow(MediaStore.Audio.Media._ID))
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI.buildUpon().appendPath(id.toString()).build()
        val albumId = getLong(MediaStore.Audio.Media.ALBUM_ID)
        val coverUri = if (albumId != 0L) {
            Uri.parse("content://media/external/audio/albumart/$albumId")
        } else null
        val coverPath = coverUri?.toString() ?: extractCoverFromUri(context, uri)
        return AudioFile(
            uri = uri,
            displayName = getString(MediaStore.Audio.Media.DISPLAY_NAME) ?: "",
            title = getString(MediaStore.Audio.Media.TITLE)
                ?: (getString(MediaStore.Audio.Media.DISPLAY_NAME)?.substringBeforeLast('.') ?: ""),
            artist = getString(MediaStore.Audio.Media.ARTIST) ?: "",
            album = getString(MediaStore.Audio.Media.ALBUM) ?: "",
            duration = getLong(MediaStore.Audio.Media.DURATION),
            size = getLong(MediaStore.Audio.Media.SIZE),
            coverPath = coverPath
        )
    }

    private fun Cursor.getString(column: String): String? {
        val idx = getColumnIndex(column)
        return if (idx >= 0) getString(idx) else null
    }

    private fun Cursor.getLong(column: String): Long {
        val idx = getColumnIndex(column)
        return if (idx >= 0) getLong(idx) else 0L
    }
}

data class AudioFolder(
    val name: String,
    val fileCount: Int,
    val files: List<AudioFile>
)

data class AudioFile(
    val uri: Uri,
    val displayName: String,
    val title: String,
    val artist: String,
    val album: String,
    val duration: Long,
    val size: Long,
    val coverPath: String? = null
)
