package sk.vespal.recshot

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class GalleryItem(
    val uri: Uri,
    val isVideo: Boolean,
    val dateAdded: Long,
    val durationMs: Long,
)

/** Ukladanie a načítanie nahrávok/snímok v priečinkoch Movies/RecShotC8 a Pictures/RecShotC8. */
object MediaStoreUtil {
    const val FOLDER = "RecShotC8"

    private fun stamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private val videoCollection: Uri
        get() = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    private val imageCollection: Uri
        get() = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    /** Vytvorí prázdny (zatiaľ skrytý) záznam pre video. Po dokončení zavolaj [publish]. */
    fun newVideo(ctx: Context, prefix: String = "RecShotC8"): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "${prefix}_${stamp()}.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$FOLDER")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return ctx.contentResolver.insert(videoCollection, values)
    }

    fun publish(ctx: Context, uri: Uri) {
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        ctx.contentResolver.update(uri, values, null, null)
    }

    fun delete(ctx: Context, uri: Uri) {
        try {
            ctx.contentResolver.delete(uri, null, null)
        } catch (_: Exception) {
        }
    }

    /** Uloží bitmapu ako PNG do Pictures/RecShotC8. */
    fun saveBitmap(ctx: Context, bmp: Bitmap, prefix: String = "RecShotC8"): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "${prefix}_${stamp()}.png")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$FOLDER")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = ctx.contentResolver.insert(imageCollection, values) ?: return null
        return try {
            ctx.contentResolver.openOutputStream(uri)?.use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            } ?: throw IllegalStateException("no stream")
            publish(ctx, uri)
            uri
        } catch (e: Exception) {
            delete(ctx, uri)
            null
        }
    }

    /** Skopíruje hotový súbor (napr. export z editora) do Movies/RecShotC8. */
    fun copyVideoToGallery(ctx: Context, file: File, prefix: String = "RecShotC8_edit"): Uri? {
        val uri = newVideo(ctx, prefix) ?: return null
        return try {
            ctx.contentResolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            } ?: throw IllegalStateException("no stream")
            publish(ctx, uri)
            uri
        } catch (e: Exception) {
            delete(ctx, uri)
            null
        }
    }

    /** Zoznam nahrávok a snímok tejto aplikácie, najnovšie prvé. */
    fun list(ctx: Context): List<GalleryItem> {
        val items = ArrayList<GalleryItem>()
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        val args = arrayOf("%/$FOLDER/%")

        try {
            ctx.contentResolver.query(
                videoCollection,
                arrayOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DATE_ADDED,
                    MediaStore.Video.VideoColumns.DURATION,
                ),
                selection, args, null
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val dateCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val durCol = c.getColumnIndexOrThrow(MediaStore.Video.VideoColumns.DURATION)
                while (c.moveToNext()) {
                    items.add(
                        GalleryItem(
                            uri = ContentUris.withAppendedId(videoCollection, c.getLong(idCol)),
                            isVideo = true,
                            dateAdded = c.getLong(dateCol),
                            durationMs = c.getLong(durCol),
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }

        try {
            ctx.contentResolver.query(
                imageCollection,
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_ADDED),
                selection, args, null
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val dateCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                while (c.moveToNext()) {
                    items.add(
                        GalleryItem(
                            uri = ContentUris.withAppendedId(imageCollection, c.getLong(idCol)),
                            isVideo = false,
                            dateAdded = c.getLong(dateCol),
                            durationMs = 0,
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }

        return items.sortedByDescending { it.dateAdded }
    }
}
