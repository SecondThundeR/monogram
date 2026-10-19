package org.monogram.feature.settings.ui

import android.graphics.Outline
import android.util.Size
import android.view.View
import android.view.ViewOutlineProvider
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import org.monogram.core.ui.qr.QrCode

@Composable
internal fun DeviceQrCamera(
    onScanned: (String) -> Unit,
    onFailure: () -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val onResult by rememberUpdatedState(onScanned)
    val onError by rememberUpdatedState(onFailure)
    val view =
        remember(context) {
            PreviewView(context).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                clipToOutline = true
                outlineProvider =
                    object : ViewOutlineProvider() {
                        override fun getOutline(view: View, outline: Outline) {
                            outline.setRoundRect(
                                0,
                                0,
                                view.width,
                                view.height,
                                24 * resources.displayMetrics.density,
                            )
                        }
                    }
            }
        }
    DisposableEffect(view, owner) {
        val main = ContextCompat.getMainExecutor(context)
        val executor = Executors.newSingleThreadExecutor()
        val analyzer = QrFrameAnalyzer(main, { onResult(it) }, { onError() })
        val preview = Preview.Builder().build().apply { surfaceProvider = view.surfaceProvider }
        val analysis =
            ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(1280, 720),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER,
                            )
                        )
                        .build()
                )
                .build()
                .apply { setAnalyzer(executor, analyzer) }
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        var camera: Camera? = null
        var disposed = false
        val observer = Observer<CameraState> { if (it.error != null) onError() }
        future.addListener(
            {
                if (!disposed) {
                    try {
                        provider = future.get()
                        camera =
                            provider!!.bindToLifecycle(
                                owner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                analysis,
                            )
                        camera!!.cameraInfo.cameraState.observe(owner, observer)
                    } catch (_: Exception) {
                        onError()
                    }
                }
            },
            main,
        )
        onDispose {
            disposed = true
            analyzer.stop()
            analysis.clearAnalyzer()
            camera?.cameraInfo?.cameraState?.removeObserver(observer)
            provider?.unbind(preview, analysis)
            executor.shutdown()
        }
    }
    AndroidView(factory = { view }, modifier = modifier)
}

internal class QrFrameAnalyzer(
    private val main: Executor,
    private val onScanned: (String) -> Unit,
    private val onFailure: () -> Unit,
) : ImageAnalysis.Analyzer {
    private val active = AtomicBoolean(true)
    private val delivered = AtomicBoolean(false)

    override fun analyze(image: ImageProxy) {
        try {
            if (!active.get() || delivered.get()) return
            val plane = image.planes.first()
            val buffer = plane.buffer.duplicate()
            val start = buffer.position()
            val width = image.width
            val height = image.height
            val luma = ByteArray(width * height)
            for (y in 0 until height) {
                val row = start + y * plane.rowStride
                if (plane.pixelStride == 1) {
                    buffer.position(row)
                    buffer.get(luma, y * width, width)
                } else {
                    for (x in 0 until width) luma[y * width + x] =
                        buffer.get(row + x * plane.pixelStride)
                }
            }
            // QR detection is rotation invariant; retain the full frame, including its quiet zone.
            val result = QrCode.decode(luma, width, height)
            if (result != null && delivered.compareAndSet(false, true)) {
                main.execute { if (active.get()) onScanned(result) }
            }
        } catch (_: Exception) {
            if (delivered.compareAndSet(false, true)) {
                main.execute { if (active.get()) onFailure() }
            }
        } finally {
            image.close()
        }
    }

    fun stop() {
        active.set(false)
    }
}
