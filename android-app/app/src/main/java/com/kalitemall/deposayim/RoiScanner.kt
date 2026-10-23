package com.kalitemall.deposayim

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.camera.core.ImageProxy
import android.graphics.ImageFormat
import kotlin.math.max
import kotlin.math.roundToInt

/** Coordinates normalized to the same CameraX ViewPort shared by Preview and Analysis. */
data class NormRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun valid() = left >= 0f && top >= 0f && right <= 1f && bottom <= 1f &&
        right - left >= 0.03f && bottom - top >= 0.03f
}
data class ScanZones(val code: NormRect, val size: NormRect)
data class CroppedOcrFrame(val bitmap: Bitmap, val sizeStartX: Int, val gapPx: Int)

object RoiScanner {
    /**
     * Read Y (luminance) directly from the ImageProxy buffer, mapping the two ROI
     * patches through its CameraX cropRect and rotation. No full-frame RGB Bitmap is
     * produced. This makes preprocessing much cheaper on older Android devices.
     * Distinct code/size patches are combined for ONE ML Kit OCR invocation.
     */
    fun prepare(image: ImageProxy, zones: ScanZones): CroppedOcrFrame {
        require(zones.code.valid() && zones.size.valid()) { "Hedef kutuları hazır değil" }
        require(image.format == ImageFormat.YUV_420_888) { "Desteklenmeyen kamera kare biçimi" }
        val crop=image.cropRect
        val cw=crop.width()
        val ch=crop.height()
        val rotation=((image.imageInfo.rotationDegrees % 360)+360)%360
        require(rotation == 0 || rotation == 90 || rotation == 180 || rotation == 270)
        val rw=if(rotation==90 || rotation==270) ch else cw
        val rh=if(rotation==90 || rotation==270) cw else ch
        val yPlane=image.planes[0]
        val data=yPlane.buffer
        val rowStride=yPlane.rowStride
        val pixelStride=yPlane.pixelStride
        fun extract(region: NormRect): Bitmap {
            val x1=(region.left*rw).roundToInt().coerceIn(0,rw-1)
            val y1=(region.top*rh).roundToInt().coerceIn(0,rh-1)
            val x2=(region.right*rw).roundToInt().coerceIn(x1+1,rw)
            val y2=(region.bottom*rh).roundToInt().coerceIn(y1+1,rh)
            val w=x2-x1
            val h=y2-y1
            val pixels=IntArray(w*h)
            var index=0
            for (oy in y1 until y2) {
                for(ox in x1 until x2) {
                    // Invert CameraX's clockwise rotation to sample the Y plane.
                    val sx:Int
                    val sy:Int
                    when(rotation) {
                        90 -> { sx=oy; sy=ch-1-ox }
                        180 -> { sx=cw-1-ox; sy=ch-1-oy }
                        270 -> { sx=cw-1-oy; sy=ox }
                        else -> { sx=ox; sy=oy }
                    }
                    val y=data.get((crop.top+sy)*rowStride+(crop.left+sx)*pixelStride).toInt() and 0xff
                    pixels[index++]=(0xff shl 24) or (y shl 16) or (y shl 8) or y
                }
            }
            return Bitmap.createBitmap(pixels,w,h,Bitmap.Config.ARGB_8888)
        }
        val left=extract(zones.code)
        try {
            val right=extract(zones.size)
            try {
                val gap=max(48,minOf(left.height,right.height)/2)
                val h=max(left.height,right.height)
                // Upscale only small crops; image pixels must remain legible, but
                // avoid creating an unnecessarily huge ML Kit input.
                val scale=if(h < 28) 2 else 1
                val width=(left.width + gap + right.width)*scale
                val height=h*scale
                val merged=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
                val canvas=Canvas(merged)
                canvas.drawColor(Color.WHITE)
                val paint=Paint(Paint.FILTER_BITMAP_FLAG)
                canvas.drawBitmap(left,null,android.graphics.Rect(0,0,left.width*scale,left.height*scale),paint)
                val x=(left.width+gap)*scale
                canvas.drawBitmap(right,null,
                    android.graphics.Rect(x,0,x+right.width*scale,right.height*scale),paint)
                return CroppedOcrFrame(merged,x,gap*scale)
            } finally { right.recycle() }
        } finally { left.recycle() }
    }
}
