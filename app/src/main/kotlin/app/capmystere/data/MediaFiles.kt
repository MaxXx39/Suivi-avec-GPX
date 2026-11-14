package app.capmystere.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/** Fichiers médias privés de l'application (images, audios, photos de défis). */
@Singleton
class MediaFiles @Inject constructor(@ApplicationContext private val context: Context) {
    private val dir: File get() = File(context.filesDir, "media").apply { mkdirs() }

    fun newFile(extension: String): File = File(dir, "${UUID.randomUUID()}.$extension")

    fun cacheFile(remotePath: String): File =
        File(context.cacheDir, "remote").apply { mkdirs() }.resolve(remotePath.replace('/', '_'))

    /** Importe une image choisie par l'utilisateur, réduite à [maxSide] px pour des envois légers. */
    suspend fun importImage(uri: Uri, maxSide: Int = 1600): File? = withContext(Dispatchers.IO) {
        runCatching {
            val bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return@runCatching null
            saveJpeg(scale(bitmap, maxSide))
        }.getOrNull()
    }

    suspend fun compress(file: File, maxSide: Int = 1600): File = withContext(Dispatchers.IO) {
        runCatching {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return@runCatching file
            saveJpeg(scale(bitmap, maxSide)).also { if (it != file) file.delete() }
        }.getOrDefault(file)
    }

    /** Découpe une image en grille [grid]×[grid] : chaque tuile devient un fragment d'indice indépendant. */
    suspend fun sliceImage(file: File, grid: Int): List<File> = withContext(Dispatchers.IO) {
        val src = BitmapFactory.decodeFile(file.absolutePath) ?: return@withContext emptyList()
        val side = minOf(src.width, src.height)
        val square = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        val cell = side / grid
        (0 until grid * grid).map { i ->
            saveJpeg(Bitmap.createBitmap(square, (i % grid) * cell, (i / grid) * cell, cell, cell))
        }
    }

    private fun scale(b: Bitmap, maxSide: Int): Bitmap {
        val longest = max(b.width, b.height)
        if (longest <= maxSide) return b
        val f = maxSide.toFloat() / longest
        return Bitmap.createScaledBitmap(b, (b.width * f).toInt(), (b.height * f).toInt(), true)
    }

    private fun saveJpeg(b: Bitmap): File = newFile("jpg").also { f -> f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 85, it) } }
}
