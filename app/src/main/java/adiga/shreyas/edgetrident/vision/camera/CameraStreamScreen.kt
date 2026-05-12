package adiga.shreyas.edgetrident.vision.camera

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@Composable
fun CameraStreamScreen(
    lifecycleOwner: LifecycleOwner,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var requestedVulkan by remember { mutableStateOf(true) }
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var metrics by remember(requestedVulkan) {
        mutableStateOf(CameraInferenceMetrics.idle(requestedVulkan))
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        if (hasCameraPermission) {
            CameraPreviewWithAnalysis(
                lifecycleOwner = lifecycleOwner,
                requestedVulkan = requestedVulkan,
                onMetrics = { metrics = it },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            PermissionPrompt(
                onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                modifier = Modifier.align(Alignment.Center),
            )
        }

        MetricsOverlay(
            metrics = metrics,
            requestedVulkan = requestedVulkan,
            onRequestedVulkanChange = { requestedVulkan = it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
        )
    }
}

@Composable
private fun CameraPreviewWithAnalysis(
    lifecycleOwner: LifecycleOwner,
    requestedVulkan: Boolean,
    onMetrics: (CameraInferenceMetrics) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val analyzer = remember(context, requestedVulkan) {
        CameraInferenceAnalyzer(
            context = context,
            requestedVulkan = requestedVulkan,
            onMetrics = { metrics -> mainExecutor.execute { onMetrics(metrics) } },
        )
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier,
    )

    DisposableEffect(lifecycleOwner, previewView, analyzer) {
        val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        var cameraProvider: ProcessCameraProvider? = null
        var imageAnalysis: ImageAnalysis? = null

        val bindCamera = Runnable {
            val provider = cameraProviderFuture.get()
            cameraProvider = provider

            val preview = Preview.Builder()
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            @Suppress("DEPRECATION")
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also { it.setAnalyzer(cameraExecutor, analyzer) }

            imageAnalysis = analysis
            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis,
            )
        }

        cameraProviderFuture.addListener(bindCamera, mainExecutor)

        onDispose {
            imageAnalysis?.clearAnalyzer()
            cameraProvider?.unbindAll()
            analyzer.close()
            cameraExecutor.shutdown()
        }
    }
}

@Composable
private fun PermissionPrompt(
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Camera permission is required to measure streaming inference.",
            color = Color.White,
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Button(onClick = onRequestPermission) {
            Text("Allow camera")
        }
    }
}

@Composable
private fun MetricsOverlay(
    metrics: CameraInferenceMetrics,
    requestedVulkan: Boolean,
    onRequestedVulkanChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = Color.Black.copy(alpha = 0.74f),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Camera inference probe",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "GPU",
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(
                        checked = requestedVulkan,
                        onCheckedChange = onRequestedVulkanChange,
                    )
                }
            }

            MetricRow(
                "Frame",
                "${metrics.width}x${metrics.height} @ ${metrics.rotationDegrees} deg",
            )
            MetricRow("FPS", format(metrics.fps))
            MetricRow("Copy", "${format(metrics.copyMs)} ms")
            MetricRow(
                "Inference",
                if (metrics.inferenceRan) {
                    "${format(metrics.inferenceMs)} ms"
                } else {
                    "not run"
                },
            )
            MetricRow("Total", "${format(metrics.totalMs)} ms")
            LaneMetricRow(metrics.yoloLane)
            LaneMetricRow(metrics.poseLane)
            MetricRow("Frames", metrics.frameCount.toString())
            MetricRow("Path", metrics.copyPath)
            MetricRow("Native", metrics.nativeCapabilities)
            MetricRow("Model", metrics.modelStatus)
            metrics.lastError?.let { MetricRow("Last error", it) }
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.74f),
            style = MaterialTheme.typography.labelMedium,
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = value,
            color = Color.White,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun LaneMetricRow(lane: InferenceLaneMetrics) {
    val state = when {
        !lane.ready -> "not ready"
        lane.ran -> "ran"
        else -> "ready"
    }
    val suffix = lane.error?.let { " err=$it" }.orEmpty()
    MetricRow(
        lane.lane,
        "${format(lane.durationMs)} ms, count=${lane.resultCount}, $state, ${lane.status}$suffix",
    )
}

private fun format(value: Double): String =
    String.format(Locale.US, "%.2f", value)
