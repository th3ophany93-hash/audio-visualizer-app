package com.audiovisualizer.render.gl

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** A unit quad spanning NDC (-1..1) with 0..1 texture coordinates, drawn as a triangle strip. */
class QuadMesh {

    private val vertexData = floatArrayOf(
        // x,    y,    u,   v
        -1f, -1f, 0f, 1f,
        1f, -1f, 1f, 1f,
        -1f, 1f, 0f, 0f,
        1f, 1f, 1f, 0f
    )

    private val vertexBuffer: FloatBuffer = ByteBuffer.allocateDirect(vertexData.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply { put(vertexData); position(0) }

    fun draw(positionAttr: Int, texCoordAttr: Int) {
        val stride = 4 * 4 // 4 floats per vertex * 4 bytes

        vertexBuffer.position(0)
        GLES30.glEnableVertexAttribArray(positionAttr)
        GLES30.glVertexAttribPointer(positionAttr, 2, GLES30.GL_FLOAT, false, stride, vertexBuffer)

        vertexBuffer.position(2)
        GLES30.glEnableVertexAttribArray(texCoordAttr)
        GLES30.glVertexAttribPointer(texCoordAttr, 2, GLES30.GL_FLOAT, false, stride, vertexBuffer)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        GLES30.glDisableVertexAttribArray(positionAttr)
        GLES30.glDisableVertexAttribArray(texCoordAttr)
    }
}
