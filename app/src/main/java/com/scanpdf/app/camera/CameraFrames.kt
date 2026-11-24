package com.scanpdf.app.camera

import androidx.camera.core.ImageProxy
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat

internal object CameraFrames {

    /**
     * The luminance (Y) plane of a YUV_420_888 frame as an upright grayscale Mat. The Y plane is
     * already the grey image the detector needs, so no colour conversion is done per frame.
     */
    fun uprightGray(image: ImageProxy): Mat {
        val plane = image.planes[0]
        val w = image.width
        val h = image.height
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val mat = Mat(h, w, CvType.CV_8UC1)
        if (rowStride == w) {
            val all = ByteArray(w * h)
            buffer.rewind()
            buffer.get(all, 0, minOf(all.size, buffer.remaining()))
            mat.put(0, 0, all)
        } else {
            val row = ByteArray(w)
            for (y in 0 until h) {
                buffer.position(y * rowStride)
                buffer.get(row, 0, w)
                mat.put(y, 0, row)
            }
        }
        val code = when (image.imageInfo.rotationDegrees) {
            90 -> Core.ROTATE_90_CLOCKWISE
            180 -> Core.ROTATE_180
            270 -> Core.ROTATE_90_COUNTERCLOCKWISE
            else -> return mat
        }
        val upright = Mat()
        Core.rotate(mat, upright, code)
        mat.release()
        return upright
    }
}
