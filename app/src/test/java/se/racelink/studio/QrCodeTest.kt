package se.racelink.studio

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class QrCodeTest {
    @Test
    fun personalPageUrlTrimsAndAppendsTheToken() {
        assertEquals("https://live.example.se/a/abc123", personalPageUrl("https://live.example.se/", "abc123"))
        assertEquals("https://live.example.se/a/abc123", personalPageUrl("  https://live.example.se  ", "abc123"))
        assertEquals("http://10.0.2.2:8080/a/t", personalPageUrl("http://10.0.2.2:8080/", "t"))
    }

    @Test
    fun qrIsSquareWithAFinderPatternInsideTheQuietZone() {
        val url = personalPageUrl("https://live.example.se", PublishProjection.tokenFor("sess", 1))
        val matrix = encodeQr(url)

        assertEquals(matrix.width, matrix.height)
        // QR module counts are always 21 + 4k; margin 2 adds four rows/columns in total.
        assertEquals(1, (matrix.width - 4) % 4)
        assertFalse("quiet zone stays light", matrix.get(0, 0))
        assertTrue("top-left finder corner is dark", matrix.get(2, 2))
    }

    @Test
    fun encodingIsDeterministicForTheSameText() {
        val url = "https://live.example.se/a/deadbeef"
        assertEquals(encodeQr(url).toString(), encodeQr(url).toString())
    }

    @Test
    fun generatedCodeDecodesBackToThePersonalUrl() {
        val url = personalPageUrl("https://live.example.se", PublishProjection.tokenFor("sess-42", 7))
        val matrix = encodeQr(url)
        // Decode an upscaled render, i.e. what the sheet actually shows on screen.
        val scale = 8
        val side = matrix.width * scale
        val pixels = IntArray(side * side) { index ->
            val moduleX = index % side / scale
            val moduleY = index / side / scale
            if (matrix.get(moduleX, moduleY)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(side, side, pixels)))
        val decoded = MultiFormatReader()
            .decode(bitmap, mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))

        assertEquals(url, decoded.text)
    }

    @Test
    fun blankTextIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { encodeQr("   ") }
    }
}
