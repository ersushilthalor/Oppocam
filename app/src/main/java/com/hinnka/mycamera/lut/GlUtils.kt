package com.hinnka.mycamera.lut

import android.opengl.GLES30
import com.hinnka.mycamera.utils.PLog

/**
 * OpenGL ES utilities for MGC EIS shaders and programs.
 */
object GlUtils {
    private const val TAG = "GlUtils"

    fun compileShader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        if (shader == 0) {
            PLog.e(TAG, "Failed to create shader")
            return 0
        }
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)

        val compileStatus = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] == 0) {
            val errorLog = GLES30.glGetShaderInfoLog(shader)
            PLog.e(TAG, "Shader compilation failed: $errorLog")
            GLES30.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    fun linkProgram(vertexShader: Int, fragmentShader: Int): Int {
        val program = GLES30.glCreateProgram()
        if (program == 0) {
            PLog.e(TAG, "Failed to create program")
            return 0
        }
        GLES30.glAttachShader(program, vertexShader)
        GLES30.glAttachShader(program, fragmentShader)
        GLES30.glLinkProgram(program)

        val linkStatus = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            val errorLog = GLES30.glGetProgramInfoLog(program)
            PLog.e(TAG, "Program linking failed: $errorLog")
            GLES30.glDeleteProgram(program)
            return 0
        }
        return program
    }
}
