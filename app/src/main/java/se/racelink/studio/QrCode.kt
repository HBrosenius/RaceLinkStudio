package se.racelink.studio

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** The bookmarkable personal page for one skier: `{base}/a/{token}`. */
internal fun personalPageUrl(serverUrl: String, token: String): String =
    serverUrl.trim().trimEnd('/') + "/a/" + token

/**
 * Encodes [text] as a QR matrix. A [size] of 0 keeps one pixel per module, which the UI scales up
 * with nearest-neighbour filtering for a crisp render; [margin] is the quiet zone in modules.
 * Pure Java (ZXing), so it runs offline and is unit-testable off-device.
 */
internal fun encodeQr(text: String, size: Int = 0, margin: Int = 2): BitMatrix {
    require(text.isNotBlank()) { "Tom text kan inte bli en QR-kod" }
    return QRCodeWriter().encode(
        text,
        BarcodeFormat.QR_CODE,
        size,
        size,
        mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to margin,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        ),
    )
}
