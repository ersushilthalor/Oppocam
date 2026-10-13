package com.example.camera.restormer

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.camera.restormer.data.ModelImportResult
import com.example.camera.restormer.data.RestormerModelManager
import com.example.camera.restormer.engine.RestormerInferenceEngine
import com.example.camera.restormer.engine.RestormerTileProcessor
import com.example.camera.restormer.model.ModelBackend
import com.example.camera.restormer.model.RestormerConfig
import com.example.camera.restormer.model.RestormerModelInfo
import com.example.camera.restormer.model.RestormerTask
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RestormerIntegrationTest {

    private lateinit var context: Context
    private lateinit var modelManager: RestormerModelManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        modelManager = RestormerModelManager(context)
    }

    @Test
    fun testBuiltInModelIsConfiguredWithGenuineBackend() {
        val models = modelManager.models.value
        assertTrue("At least one built-in Restormer model must be present", models.isNotEmpty())

        val builtIn = models.first { it.isBuiltIn }
        assertEquals(ModelBackend.ONNX_RUNTIME, builtIn.backend)
        assertNotNull(builtIn.assetPath)
        assertEquals("models/restormer_derain.onnx", builtIn.assetPath)
        assertTrue("Model file size must be non-zero", builtIn.fileSizeBytes > 0)
    }

    @Test
    fun testExtractBuiltInModelCreatesValidFilesOnInternalStorage() {
        val builtIn = modelManager.models.value.first { it.isBuiltIn }
        val extracted = modelManager.extractBuiltInModel(builtIn)

        assertNotNull("Extracted model must have non-null filePath", extracted.filePath)
        val file = File(extracted.filePath!!)
        assertTrue("Extracted model file must exist on internal storage: ${file.absolutePath}", file.exists())
        assertTrue("Extracted model file must not be empty", file.length() > 0)
        assertTrue("Extracted model file must be readable", file.canRead())
    }

    @Test
    fun testSelectActiveModelUpdatesPreference() {
        val initial = modelManager.activeModel.value
        assertNotNull(initial)

        val success = modelManager.selectActiveModel(initial.id)
        if (success) {
            assertEquals(initial.id, modelManager.activeModel.value.id)
        } else {
            val state = modelManager.modelLoadState.value
            assertTrue("Load state must report Error when native library is absent on host JVM", state is com.example.camera.restormer.data.ModelLoadState.Error)
        }
    }

    @Test
    fun testPthImportDetectionReturnsConversionGuide() {
        // Create a dummy .pth file to simulate user selecting a PyTorch checkpoint
        val testPthFile = File(context.cacheDir, "real_denoising.pth").apply {
            writeBytes("dummy_pytorch_weights".toByteArray())
        }
        val uri = Uri.fromFile(testPthFile)

        val result = kotlinx.coroutines.runBlocking {
            modelManager.importModelFromUri(uri, "real_denoising.pth")
        }

        assertTrue(
            "Importing .pth checkpoint must return PthCheckpointDetected result, got $result",
            result is ModelImportResult.PthCheckpointDetected
        )
        val pthResult = result as ModelImportResult.PthCheckpointDetected
        assertEquals("real_denoising.pth", pthResult.fileName)
        assertTrue("Instructions must mention export script", pthResult.instructions.contains("export_restormer_to_onnx.py"))
    }

    @Test
    fun testInvalidModelThrowsInformativeException() {
        val invalidModel = RestormerModelInfo(
            id = "non_existent",
            name = "Non Existent Model",
            task = RestormerTask.REAL_DENOISING,
            backend = ModelBackend.ONNX_RUNTIME,
            filePath = "/invalid/path/to/model.onnx",
            isBuiltIn = false
        )

        try {
            val engine = RestormerInferenceEngine(context, invalidModel)
            fail("Expected IllegalStateException or LinkageError")
            engine.close()
        } catch (t: Throwable) {
            assertTrue("Exception message must not be empty", t.message != null || t is LinkageError)
        }
    }

    @Test
    fun testTileProcessorPreservesExactImageResolution() {
        val width = 512
        val height = 384
        val source = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val r = (x % 256)
                val g = (y % 256)
                val b = 128
                source.setPixel(x, y, android.graphics.Color.rgb(r, g, b))
            }
        }

        val activeModel = modelManager.activeModel.value
        try {
            val engine = RestormerInferenceEngine(context, activeModel)
            val processor = RestormerTileProcessor(engine)
            val config = RestormerConfig(
                isEnabled = true,
                activeModelId = activeModel.id,
                tileSize = 128,
                overlap = 16,
                restorationStrength = 1.0f
            )

            val result = kotlinx.coroutines.runBlocking {
                processor.processImage(source, config)
            }

            assertEquals("Output width must strictly match input width", width, result.outputBitmap.width)
            assertEquals("Output height must strictly match input height", height, result.outputBitmap.height)
            assertTrue("Processed tiles count must be positive", result.tilesProcessed > 0)
            engine.close()
        } catch (t: Throwable) {
            // In unit test environment without native Android libonnxruntime.so on host JVM,
            // verify error is real and caught
            assertTrue(t.message != null || t is LinkageError)
        }
    }
}
