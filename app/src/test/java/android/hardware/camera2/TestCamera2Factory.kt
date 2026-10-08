package android.hardware.camera2

import android.hardware.camera2.params.InputConfiguration
import android.hardware.camera2.params.OutputConfiguration
import android.os.Handler
import android.view.Surface
import java.util.concurrent.Executor

object TestCamera2Factory {

    class TrackingCameraDevice(
        private val cameraId: String
    ) : CameraDevice() {
        var isClosed: Boolean = false
            private set

        override fun getId(): String = cameraId

        override fun createCaptureSession(
            outputs: MutableList<Surface>,
            callback: CameraCaptureSession.StateCallback,
            handler: Handler?
        ) {}

        @Suppress("OVERRIDE_DEPRECATION")
        override fun createCaptureSessionByOutputConfigurations(
            outputConfigurations: MutableList<OutputConfiguration>,
            callback: CameraCaptureSession.StateCallback,
            handler: Handler?
        ) {}

        @Suppress("OVERRIDE_DEPRECATION")
        override fun createReprocessableCaptureSession(
            inputConfig: InputConfiguration,
            outputs: MutableList<Surface>,
            callback: CameraCaptureSession.StateCallback,
            handler: Handler?
        ) {}

        @Suppress("OVERRIDE_DEPRECATION")
        override fun createReprocessableCaptureSessionByConfigurations(
            inputConfig: InputConfiguration,
            outputs: MutableList<OutputConfiguration>,
            callback: CameraCaptureSession.StateCallback,
            handler: Handler?
        ) {}

        @Suppress("OVERRIDE_DEPRECATION")
        override fun createConstrainedHighSpeedCaptureSession(
            outputs: MutableList<Surface>,
            callback: CameraCaptureSession.StateCallback,
            handler: Handler?
        ) {}

        override fun createCaptureRequest(templateType: Int): CaptureRequest.Builder {
            throw UnsupportedOperationException()
        }

        override fun createReprocessCaptureRequest(inputResult: TotalCaptureResult): CaptureRequest.Builder {
            throw UnsupportedOperationException()
        }

        override fun close() {
            isClosed = true
        }
    }

    class TrackingCaptureSession(
        private val ownerDevice: CameraDevice
    ) : CameraCaptureSession() {
        var isStoppedRepeating: Boolean = false
            private set
        var isClosed: Boolean = false
            private set

        override fun getDevice(): CameraDevice = ownerDevice
        override fun prepare(surface: Surface) {}
        override fun finalizeOutputConfigurations(outputConfigs: MutableList<OutputConfiguration>) {}

        override fun capture(
            request: CaptureRequest,
            listener: CaptureCallback?,
            handler: Handler?
        ): Int = 1

        override fun captureSingleRequest(
            request: CaptureRequest,
            executor: Executor,
            listener: CaptureCallback
        ): Int = 1

        override fun captureBurst(
            requests: MutableList<CaptureRequest>,
            listener: CaptureCallback?,
            handler: Handler?
        ): Int = 1

        override fun captureBurstRequests(
            requests: MutableList<CaptureRequest>,
            executor: Executor,
            listener: CaptureCallback
        ): Int = 1

        override fun setRepeatingRequest(
            request: CaptureRequest,
            listener: CaptureCallback?,
            handler: Handler?
        ): Int = 1

        override fun setSingleRepeatingRequest(
            request: CaptureRequest,
            executor: Executor,
            listener: CaptureCallback
        ): Int = 1

        override fun setRepeatingBurst(
            requests: MutableList<CaptureRequest>,
            listener: CaptureCallback?,
            handler: Handler?
        ): Int = 1

        override fun setRepeatingBurstRequests(
            requests: MutableList<CaptureRequest>,
            executor: Executor,
            listener: CaptureCallback
        ): Int = 1

        override fun stopRepeating() {
            isStoppedRepeating = true
        }

        override fun abortCaptures() {}
        override fun isReprocessable(): Boolean = false
        override fun getInputSurface(): Surface? = null

        override fun close() {
            isClosed = true
        }
    }
}
