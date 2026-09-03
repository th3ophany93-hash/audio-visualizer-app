package com.audiovisualizer.render.gl

import android.opengl.GLES30

/** Compiles and links a GLSL ES 3.0 vertex/fragment pair into a usable program. */
class ShaderProgram(vertexSource: String, fragmentSource: String) {

    val programId: Int = linkProgram(
        compileShader(GLES30.GL_VERTEX_SHADER, vertexSource),
        compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
    )

    fun use() {
        GLES30.glUseProgram(programId)
    }

    fun attributeLocation(name: String): Int = GLES30.glGetAttribLocation(programId, name)

    fun uniformLocation(name: String): Int = GLES30.glGetUniformLocation(programId, name)

    fun release() {
        GLES30.glDeleteProgram(programId)
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            error("Shader compile failed: $log")
        }
        return shader
    }

    private fun linkProgram(vertexShader: Int, fragmentShader: Int): Int {
        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vertexShader)
        GLES30.glAttachShader(program, fragmentShader)
        GLES30.glLinkProgram(program)
        val status = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
        GLES30.glDeleteShader(vertexShader)
        GLES30.glDeleteShader(fragmentShader)
        if (status[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(program)
            GLES30.glDeleteProgram(program)
            error("Program link failed: $log")
        }
        return program
    }
}
