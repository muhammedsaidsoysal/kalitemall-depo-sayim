package com.kalitemall.deposayim

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import androidx.camera.core.ImageProxy
import kotlin.math.max
import kotlin.math.roundToInt

/** Relative to the CameraX PreviewView content when Preview and Analysis share a ViewPort. */
data class NormRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun valid(): Boolean = left >= 0f && top >= 0f && right <= 1f && bottom <= 1f &&
        right - left >= .03f && bottom - top >= .03f
}

data class ScanZones(val code: NormRect, val size: NormRect)

data class CroppedOcrFrame(val bitmap: Bitmap, val sizeStartX: Int, val gapPx: Int)

object RoiScanner {
    /**
     * ImageProxy.toBitmap converts the current YUV camera frame to RGB. Apply the CameraX
     * ViewPort crop and rotation before using UI-relative normalized scan regions.
     * Combine BOTH cropped patches into ONE ML Kit call with a wide white separator.
     * Neither image is sent to the server. No full-screen text detection in fast mode.
     */
    fun prepare(image: ImageProxy, zones: ScanZones): CroppedOcrFrame {
        require(zones.code.valid() && zones.size.valid()) { "Tarama bölgeleri hazır değil" }
        val full = image.toBitmap()
        val crop = image.cropRect
        val rotation = image.imageInfo.rotationDegrees
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        val visible = try {
            Bitmap.createBitmap(full, crop.left, crop.top, crop.width(), crop.height(), matrix, true)
        } finally {
            // createBitmap can return the original bitmap for an identity crop.
            // In that case it is released together with 'visible' below.
        }
        if (visible !== full) full.recycle()
        try {
            val left = clipCrop(visible, zones.code)
            val right = clipCrop(visible, zones.size)
            try {
                val gap = max(64, minOf(left.height, right.height) / 2)
                val merged = Bitmap.createBitmap(left.width + gap + right.width,
                    max(left.height, right.height), Bitmap.Config.ARGB_8888)
                val canvas = Canvas(merged)
                canvas.drawColor(Color.WHITE)
                val paint = Paint(Paint.FILTER_BITMAP_FLAG)
                canvas.drawBitmap(left, 0f, 0f, paint)
                canvas.drawBitmap(right, (left.width + gap).toFloat(), 0f, paint)
                return CroppedOcrFrame(merged, left.width + gap, gap)
            } finally {
                left.recycle()
                right.recycle()
            }
        } finally {
            visible.recycle()
        }
    }

    private fun clipCrop(source: Bitmap, rect: NormRect): Bitmap {
        val x = (rect.left * source.width).roundToInt().coerceIn(0, source.width - 1)
        val y = (rect.top * source.height).roundToInt().coerceIn(0, source.height - 1)
        val right = (rect.right * source.width).roundToInt().coerceIn(x + 1, source.width)
        val bottom = (rect.bottom * source.height).roundToInt().coerceIn(y + 1, source.height)
        return Bitmap.createBitmap(source, x, y, right - x, bottom - y)
    }
}
