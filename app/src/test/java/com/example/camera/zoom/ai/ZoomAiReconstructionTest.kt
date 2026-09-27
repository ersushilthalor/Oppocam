package com.example.camera.zoom.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.example.camera.zoom.HighQualityZoomEngine
import com.example.camera.zoom.ZoomProcessingQuality
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ZoomAiReconstructionTest {

    private lateinit var context: Context
    private lateinit var repository: ZoomAiModelRepository
    private lateinit var zoomEngine: HighQualityZoomEngine

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        repository = ZoomAiModelRepository.getInstance(context)
        zoomEngine = HighQualityZoomEngine.getInstance(context)
    }

    @Test
    fun testImportAndValidateHatAndBsrganModels(): Unit = runBlocking {
        // 1. Prepare & import HAT_SRx2 pretrained weights
        val hatResult = repository.preparePretrainedArchitectureModel(
            architecture = ZoomAiModelArchitecture.HAT,
            scaleFactor = 2,
            useSafetensors = false
        )
        assertTrue("HAT model import should succeed", hatResult.isSuccess)
        val hatModel = hatResult.getOrThrow()
        assertEquals(ZoomAiModelArchitecture.HAT, hatModel.architecture)
        assertEquals(2, hatModel.scaleFactor)
        assertEquals(16, hatModel.windowSize)
        assertTrue(hatModel.fileSizeBytes > 1000L)
        assertEquals(ZoomAiLoadingStatus.LOADED_READY, repository.hatLoadingStatus.value)
        assertEquals(hatModel.id, repository.selectedHatModel.value?.id)

        // 2. Prepare & import BSRGANx2 pretrained weights
        val bsrganResult = repository.preparePretrainedArchitectureModel(
            architecture = ZoomAiModelArchitecture.BSRGAN,
            scaleFactor = 2,
            useSafetensors = true
        )
        assertTrue("BSRGAN model import should succeed", bsrganResult.isSuccess)
        val bsrganModel = bsrganResult.getOrThrow()
        assertEquals(ZoomAiModelArchitecture.BSRGAN, bsrganModel.architecture)
        assertEquals(2, bsrganModel.scaleFactor)
        assertTrue(bsrganModel.fileSizeBytes > 1000L)
        assertEquals(ZoomAiLoadingStatus.LOADED_READY, repository.bsrganLoadingStatus.value)
        assertEquals(bsrganModel.id, repository.selectedBsrganModel.value?.id)
    }

    @Test
    fun testRejectCrossArchitectureMismatch(): Unit = runBlocking {
        val tempBsrganFile = File(context.cacheDir, "BSRGANx2_test.pth")
        ZoomAiWeightParser.exportPretrainedWeightsFile(
            targetFile = tempBsrganFile,
            architecture = ZoomAiModelArchitecture.BSRGAN,
            scaleFactor = 2
        )

        // Attempting to import a BSRGAN model into the HAT slot must fail with a clear error message
        val wrongSlotResult = repository.importModelFromFile(
            file = tempBsrganFile,
            displayName = "BSRGANx2_test.pth",
            targetArchitecture = ZoomAiModelArchitecture.HAT
        )
        assertTrue("Importing BSRGAN into HAT slot must fail validation", wrongSlotResult.isFailure)
        val errorMsg = repository.hatErrorMessage.value ?: ""
        assertTrue("Error message should explain architecture incompatibility", errorMsg.contains("Incompatible", ignoreCase = true))
        tempBsrganFile.delete()
    }

    @Test
    fun testHatAndBsrganZoomedImageReconstructionPipeline(): Unit = runBlocking {
        // Create a non-multiple-of-16 test zoomed bitmap (e.g., 50x38) to verify HAT window_size=16 reflection padding & crop
        val srcW = 50
        val srcH = 38
        val testBitmap = Bitmap.createBitmap(srcW, srcH, Bitmap.Config.ARGB_8888)
        for (y in 0 until srcH) {
            for (x in 0 until srcW) {
                val r = (x * 255) / srcW
                val g = (y * 255) / srcH
                val b = if ((x + y) % 4 < 2) 220 else 40
                testBitmap.setPixel(x, y, Color.rgb(r, g, b))
            }
        }

        // 1. Test HAT 2x AI Reconstruction
        repository.preparePretrainedArchitectureModel(ZoomAiModelArchitecture.HAT, scaleFactor = 2)
        repository.setReconstructionMode(ZoomReconstructionMode.HAT)

        var lastProgress = 0f
        val hatOutput = zoomEngine.processZoomedBurstWithSelectedMode(
            burstBitmaps = listOf(testBitmap),
            zoomRatio = 4.0f,
            quality = ZoomProcessingQuality.BALANCED,
            onProgress = { lastProgress = it }
        )
        assertEquals(srcW * 2, hatOutput.width)
        assertEquals(srcH * 2, hatOutput.height)
        assertEquals(1.0f, lastProgress, 0.01f)

        // 2. Test BSRGAN 2x AI Reconstruction
        repository.preparePretrainedArchitectureModel(ZoomAiModelArchitecture.BSRGAN, scaleFactor = 2)
        repository.setReconstructionMode(ZoomReconstructionMode.BSRGAN)

        val bsrganOutput = zoomEngine.processZoomedBurstWithSelectedMode(
            burstBitmaps = listOf(testBitmap),
            zoomRatio = 5.0f,
            quality = ZoomProcessingQuality.BALANCED,
            onProgress = { lastProgress = it }
        )
        assertEquals(srcW * 2, bsrganOutput.width)
        assertEquals(srcH * 2, bsrganOutput.height)
    }
}
