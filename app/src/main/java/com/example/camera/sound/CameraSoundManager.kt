package com.example.camera.sound

import android.media.MediaActionSound
import android.util.Log

/**
 * High-performance, low-latency audio manager for camera sound effects.
 * Plays authentic, short, realistic mechanical shutter and video chime feedback
 * via Android's native MediaActionSound.
 */
object CameraSoundManager {
    private const val TAG = "CameraSoundManager"
    private var actionSound: MediaActionSound? = null

    init {
        try {
            val sound = MediaActionSound()
            sound.load(MediaActionSound.SHUTTER_CLICK)
            sound.load(MediaActionSound.START_VIDEO_RECORDING)
            sound.load(MediaActionSound.STOP_VIDEO_RECORDING)
            actionSound = sound
        } catch (e: Exception) {
            Log.w(TAG, "MediaActionSound initialization deferred: ${e.message}")
        }
    }

    fun playShutter() {
        try {
            actionSound?.play(MediaActionSound.SHUTTER_CLICK)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to play shutter sound: ${e.message}")
        }
    }

    fun playStartVideo() {
        try {
            actionSound?.play(MediaActionSound.START_VIDEO_RECORDING)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to play video start sound: ${e.message}")
        }
    }

    fun playStopVideo() {
        try {
            actionSound?.play(MediaActionSound.STOP_VIDEO_RECORDING)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to play video stop sound: ${e.message}")
        }
    }

    fun release() {
        try {
            actionSound?.release()
            actionSound = null
        } catch (ignored: Exception) {}
    }
}
