package app.capmystere.ui.common

import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.capmystere.R
import app.capmystere.theme.Cap
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.io.File
import java.util.concurrent.Executors

fun Context.hasPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

/**
 * Garde d'autorisation caméra : explique d'abord pourquoi, demande ensuite.
 * [rationale] est affiché tant que l'autorisation n'est pas accordée.
 */
@Composable
fun CameraGate(rationale: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(context.hasPermission(android.Manifest.permission.CAMERA)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    if (granted) content() else {
        Column(modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            GlyphIcon(Glyph.CAMERA, size = 56.dp, tint = Cap.colors.primary)
            Spacer(Modifier.height(18.dp))
            DisplayText(stringResource(R.string.perm_camera_title), style = Cap.type.title, textAlign = TextAlign.Center)
            Spacer(Modifier.height(10.dp))
            BodyText(rationale, color = Cap.colors.textMuted, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            CapButton(stringResource(R.string.perm_allow), { launcher.launch(android.Manifest.permission.CAMERA) })
        }
    }
}

/** Lecteur de QR code plein cadre (CameraX + ZXing, sans service Google). */
@Composable
fun QrScanner(onResult: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val delivered = remember { booleanArrayOf(false) }
    DisposableEffect(owner) {
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            provider = future.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            val reader = MultiFormatReader().apply { setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))) }
            val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analysis.setAnalyzer(executor) { image ->
                try {
                    val plane = image.planes[0]
                    val bytes = ByteArray(plane.buffer.remaining()).also { plane.buffer.get(it) }
                    val source = PlanarYUVLuminanceSource(bytes, plane.rowStride, image.height, 0, 0, image.width, image.height, false)
                    val text = runCatching { reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text }.getOrNull()
                    if (text != null && !delivered[0]) {
                        delivered[0] = true
                        ContextCompat.getMainExecutor(context).execute { onResult(text) }
                    }
                } finally {
                    reader.reset()
                    image.close()
                }
            }
            runCatching {
                provider?.unbindAll()
                provider?.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose { provider?.unbindAll(); executor.shutdown() }
    }
    Box(modifier) {
        AndroidView({ previewView }, Modifier.fillMaxSize())
        // Viseur
        Box(Modifier.align(Alignment.Center).size(230.dp).border(2.dp, Cap.colors.primary, buttonShape()))
    }
}

/** Prise de photo intégrée : aperçu + déclencheur. */
@Composable
fun PhotoCapture(output: File, onCaptured: (File) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    var busy by remember { mutableStateOf(false) }
    DisposableEffect(owner) {
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            provider = future.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            runCatching {
                provider?.unbindAll()
                provider?.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose { provider?.unbindAll() }
    }
    Box(modifier) {
        AndroidView({ previewView }, Modifier.fillMaxSize())
        val description = stringResource(R.string.photo_shutter)
        Box(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 36.dp).size(78.dp).clip(CircleShape)
                .background(Color.White.copy(alpha = if (busy) 0.4f else 0.95f)).border(4.dp, Cap.colors.primary, CircleShape)
                .clickable(enabled = !busy, onClickLabel = description) {
                    busy = true
                    capture.takePicture(
                        ImageCapture.OutputFileOptions.Builder(output).build(), ContextCompat.getMainExecutor(context),
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(result: ImageCapture.OutputFileResults) { busy = false; onCaptured(output) }
                            override fun onError(e: ImageCaptureException) { busy = false }
                        },
                    )
                },
        )
    }
}
