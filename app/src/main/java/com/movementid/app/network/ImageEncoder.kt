package com.movementid.app.network

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * A full-resolution phone JPEG is several MB; base64 inflates that by ~33% and it all travels in
 * one JSON body. Free-tier providers frequently reject payloads that large, and they bill image
 * tokens by size anyway. A movement filling the frame stays legible well below full sensor
 * resolution, so downscale and re-compress before sending.
 */
internal object ImageEncoder {

    /**
     * Default size sent to the model. 2048px keeps most engraving legible at roughly two-thirds
     * the image-token cost of 2560 — and on a free tier, tokens are what run out.
     */
    const val STANDARD_EDGE_PX = 2048

    /**
     * Used when a scan read no markings and the user asks to try sharper, or when they've opted
     * into always using it. Sub-millimetre engraving sometimes needs every pixel.
     */
    const val SHARP_EDGE_PX = 2560

    /** Higher quality too: JPEG artefacts land exactly on fine engraved lines. */
    private const val JPEG_QUALITY = 92

    fun encodeDownscaledBase64(imageFile: File, maxEdge: Int = STANDARD_EDGE_PX): String {
        val bitmap = decodeDownscaled(imageFile, maxEdge) ?: return rawBase64(imageFile)
        return try {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } finally {
            bitmap.recycle()
        }
    }

    private fun decodeDownscaled(imageFile: File, maxEdge: Int): Bitmap? {
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(imageFile.absolutePath, boundsOptions)

        val longEdge = maxOf(boundsOptions.outWidth, boundsOptions.outHeight)
        if (longEdge <= 0) return null

        var sampleSize = 1
        while (longEdge / (sampleSize * 2) >= maxEdge) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val decoded = BitmapFactory.decodeFile(imageFile.absolutePath, decodeOptions) ?: return null

        val decodedLongEdge = maxOf(decoded.width, decoded.height)
        if (decodedLongEdge <= maxEdge) return decoded

        val ratio = maxEdge.toFloat() / decodedLongEdge
        val scaled = Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * ratio).toInt().coerceAtLeast(1),
            (decoded.height * ratio).toInt().coerceAtLeast(1),
            true
        )
        if (scaled != decoded) decoded.recycle()
        return scaled
    }

    private fun rawBase64(imageFile: File): String =
        Base64.encodeToString(imageFile.readBytes(), Base64.NO_WRAP)
}
