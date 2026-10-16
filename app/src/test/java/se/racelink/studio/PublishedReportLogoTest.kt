package se.racelink.studio

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PublishedReportLogoTest {
    @Test fun publishedLogoUsesTheReportImageAndHandlesMissingOrUnreadableBranding() {
        val context = RuntimeEnvironment.getApplication()
        val file = File(context.filesDir, "report-logo.png")
        try {
            file.delete()
            assertNull(publishedReportLogo(context))
            val image = Bitmap.createBitmap(512, 256, Bitmap.Config.ARGB_8888)
            file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
            val bytes = Base64.decode(publishedReportLogo(context), Base64.DEFAULT)
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            assertEquals(256, decoded.width)
            assertEquals(128, decoded.height)
            decoded.recycle()
            file.writeText("invalid image")
            assertNull(publishedReportLogo(context))
        } finally { file.delete() }
    }
}
