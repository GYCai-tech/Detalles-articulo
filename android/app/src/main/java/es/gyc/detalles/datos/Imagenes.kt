package es.gyc.detalles.datos

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Reduce la foto antes de subirla.
 *
 * No es cosmetico. La carpeta de imagenes del ERP son 1,58 GB para 2.662
 * ficheros, y los PNG que el gestor de incidencias deja ahi sin comprimir van a
 * 3,75 MB de media. Una camara de movil produce entre 3 y 8 MB por disparo: si
 * alguien fotografia los ~600 articulos del catalogo, serian 2-4 GB de golpe,
 * mas que todo lo acumulado en ese recurso desde 2005.
 *
 * A 1600 px de lado largo y calidad 85 quedan en 200-400 KB, de sobra para una
 * ficha y para la web.
 */
object Imagenes {

    private const val LADO_MAXIMO = 1600
    private const val CALIDAD = 85

    /**
     * Devuelve un fichero nuevo, ya reducido. Si algo falla se devuelve el
     * original: es preferible subir una foto grande que perderla despues de
     * que alguien haya ido hasta el articulo a hacerla.
     */
    fun reducir(original: File): File = runCatching {
        val limites = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(original.path, limites)
        if (limites.outWidth <= 0 || limites.outHeight <= 0) return original

        // Primero se decodifica ya submuestreado, para no cargar en memoria una
        // imagen de 12 Mpx entera solo para encogerla.
        val opciones = BitmapFactory.Options().apply {
            inSampleSize = calcularMuestreo(limites.outWidth, limites.outHeight)
        }
        val mapa = BitmapFactory.decodeFile(original.path, opciones) ?: return original

        val escala = LADO_MAXIMO.toFloat() / max(mapa.width, mapa.height)
        val ajustado = if (escala < 1f) {
            Bitmap.createScaledBitmap(
                mapa,
                (mapa.width * escala).roundToInt(),
                (mapa.height * escala).roundToInt(),
                true,
            ).also { if (it != mapa) mapa.recycle() }
        } else mapa

        val girado = aplicarOrientacion(ajustado, original)

        val destino = File(original.parentFile, "r_${original.name}")
        destino.outputStream().use { girado.compress(Bitmap.CompressFormat.JPEG, CALIDAD, it) }
        girado.recycle()

        // Si el "reducido" no es mas pequeno (foto ya diminuta), no compensa.
        if (destino.length() in 1 until original.length()) {
            original.delete()
            destino
        } else {
            destino.delete()
            original
        }
    }.getOrDefault(original)

    private fun calcularMuestreo(ancho: Int, alto: Int): Int {
        var muestreo = 1
        while (max(ancho, alto) / muestreo > LADO_MAXIMO * 2) muestreo *= 2
        return muestreo
    }

    /**
     * Al escalar se pierde el EXIF, y con el la orientacion. Sin esto, las
     * fotos hechas en vertical se suben tumbadas.
     */
    private fun aplicarOrientacion(mapa: Bitmap, original: File): Bitmap {
        val grados = when (
            ExifInterface(original.path)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION,
                                 ExifInterface.ORIENTATION_NORMAL)
        ) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> return mapa
        }
        val matriz = android.graphics.Matrix().apply { postRotate(grados) }
        return Bitmap.createBitmap(mapa, 0, 0, mapa.width, mapa.height, matriz, true)
            .also { if (it != mapa) mapa.recycle() }
    }
}
