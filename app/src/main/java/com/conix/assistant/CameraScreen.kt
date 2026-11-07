package com.conix.assistant

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.conix.assistant.core.KoriCore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

object ImageUtil {
    /** JPEG bytes from the camera, rotated upright and shrunk to max 1024 px for a fast upload. */
    fun toJpeg(image: ImageProxy): ByteArray {
        val buf = image.planes[0].buffer
        val bytes = ByteArray(buf.remaining())
        buf.get(bytes)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2000) sample *= 2
        var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IllegalStateException("Could not decode photo")
        val rot = image.imageInfo.rotationDegrees
        if (rot != 0) {
            val m = Matrix()
            m.postRotate(rot.toFloat())
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
        val scale = 1024f / maxOf(bmp.width, bmp.height)
        if (scale < 1f) bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
        return out.toByteArray()
    }
}

@Composable
fun CameraScreen(core: KoriCore, question: String, onClose: () -> Unit, speak: (String) -> Unit) {
    val ctx = LocalContext.current
    val act = remember { ctx.findActivity() }
    val scope = rememberCoroutineScope()
    var hasCam by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasCam = it }
    var status by remember { mutableStateOf("Point the camera at the object...") }
    var answer by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var capture by remember { mutableStateOf<ImageCapture?>(null) }

    LaunchedEffect(Unit) { if (!hasCam) permLauncher.launch(Manifest.permission.CAMERA) }

    fun shoot() {
        val cap = capture ?: return
        if (busy) return
        busy = true
        status = "Looking..."
        cap.takePicture(ContextCompat.getMainExecutor(ctx), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val jpeg = try { ImageUtil.toJpeg(image) } catch (e: Exception) { null }
                image.close()
                if (jpeg == null) {
                    status = "Could not read the photo."; busy = false
                    return
                }
                scope.launch {
                    status = "Identifying and checking the web..."
                    val r = try { core.describeImage(jpeg, question) } catch (e: Exception) { "Error: ${e.message}" }
                    answer = r
                    status = ""
                    busy = false
                    speak(r.substringBefore("\n\nSources:").take(500))
                }
            }

            override fun onError(exception: ImageCaptureException) {
                status = "Camera error: ${exception.message}"
                busy = false
            }
        })
    }

    // take the photo automatically once the preview has had a moment to focus
    LaunchedEffect(capture) {
        if (capture != null) { delay(1800); shoot() }
    }

    DisposableEffect(Unit) {
        onDispose {
            try { ProcessCameraProvider.getInstance(ctx).get().unbindAll() } catch (_: Exception) { }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (hasCam && act != null) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { c ->
                    val pv = PreviewView(c)
                    val fut = ProcessCameraProvider.getInstance(c)
                    fut.addListener({
                        try {
                            val provider = fut.get()
                            val preview = Preview.Builder().build().also { it.setSurfaceProvider(pv.surfaceProvider) }
                            val cap = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
                            provider.unbindAll()
                            provider.bindToLifecycle(act, CameraSelector.DEFAULT_BACK_CAMERA, preview, cap)
                            capture = cap
                        } catch (e: Exception) {
                            status = "Camera error: ${e.message}"
                        }
                    }, ContextCompat.getMainExecutor(c))
                    pv
                }
            )
        } else if (!hasCam) {
            Text("Camera permission is needed.", color = Color.White, modifier = Modifier.align(Alignment.Center))
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(12.dp)) {
            CxCard {
                Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                    if (status.isNotEmpty()) Text(status, color = Cx.Muted)
                    if (answer.isNotEmpty()) Text(answer)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { answer = ""; shoot() }, enabled = !busy) { Text("Capture again") }
                OutlinedButton(onClick = onClose) { Text("Close") }
            }
        }
    }
}
