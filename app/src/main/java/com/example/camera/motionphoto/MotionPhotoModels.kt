package com.example.camera.motionphoto

enum class MotionPhotoDuration(val label: String) {
    SEC_1("1s"),
    SEC_2("2s");

    val title: String get() = label
}

data class MotionPhotoConfig(
    val enabled: Boolean = false,
    val duration: MotionPhotoDuration = MotionPhotoDuration.SEC_1
)
