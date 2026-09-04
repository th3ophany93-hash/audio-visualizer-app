package com.audiovisualizer.render.gl

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.opengl.GLES30
import android.opengl.GLUtils
import androidx.exifinterface.media.ExifInterface

/** A GL texture plus the pixel size it was uploaded at (after any EXIF-orientation correction). */
class LoadedTexture(val id: Int, val width: Int, val height: Int)

/** Decodes an image URI into a GL texture. Must be called on the GL thread. */
object TextureLoader {

    fun loadFromUri(context: Context, uri: Uri): LoadedTexture {
        val orientation = context.contentResolver.openInputStream(uri)?.use { stream ->
            ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL

        val rawBitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream)
        } ?: error("Could not decode bitmap from $uri")

        val corrected = applyExifOrientation(rawBitmap, orientation)
        val bitmap = flipVerticallyForGlUpload(corrected)
        return uploadBitmap(bitmap)
    }

    /**
     * GLUtils.texImage2D uploads a Bitmap's rows in memory order (row 0 -
     * the bitmap's top row - first), but [QuadMesh]'s UV mapping expects
     * texture row 0 to be the image's *bottom* row (the usual OpenGL
     * texture-coordinate convention, v=0 at the bottom) - without this
     * flip every image (and video frame, once that's wired up) renders
     * upside down. This is unrelated to EXIF: it reproduces even for a
     * plain, untagged image.
     */
    private fun flipVerticallyForGlUpload(bitmap: Bitmap): Bitmap {
        val matrix = Matrix().apply { postScale(1f, -1f) }
        val flipped = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (flipped !== bitmap) bitmap.recycle()
        return flipped
    }

    /** Rotates/flips a decoded bitmap so it displays upright, per its EXIF orientation tag. */
    fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_NORMAL, ExifInterface.ORIENTATION_UNDEFINED -> return bitmap
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    private fun uploadBitmap(bitmap: Bitmap): LoadedTexture {
        val textureIds = IntArray(1)
        GLES30.glGenTextures(1, textureIds, 0)
        val textureId = textureIds[0]

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)

        val width = bitmap.width
        val height = bitmap.height
        bitmap.recycle()
        return LoadedTexture(textureId, width, height)
    }
}
