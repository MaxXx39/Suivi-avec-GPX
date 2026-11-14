package app.capmystere.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** QR code dessiné en vectoriel. Toujours sombre sur clair, pour rester lisible par tous les lecteurs. */
@Composable
fun QrCode(content: String, modifier: Modifier = Modifier, size: Dp = 220.dp, dark: Color = Color(0xFF14110D), light: Color = Color(0xFFF7F1E3)) {
    val matrix = remember(content) {
        runCatching {
            QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M))
        }.getOrNull()
    }
    Box(modifier.size(size).clip(RoundedCornerShape(10.dp)).background(light).padding(14.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val m = matrix ?: return@Canvas
            val cell = this.size.width / m.width
            for (y in 0 until m.height) for (x in 0 until m.width) {
                if (m.get(x, y)) drawRect(dark, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}
