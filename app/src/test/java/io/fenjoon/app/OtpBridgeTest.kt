package io.fenjoon.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OtpBridgeTest {
    @Test
    fun `extracts five digit code from combined SMS format`() {
        val message = """
            فنجــون
            code: 12345

            gpfJaEpM2S5
            @app.fenjoon.io #12345
        """.trimIndent()

        assertEquals("12345", extractOtpCode(message))
    }

    @Test
    fun `prefers hash-prefixed web otp code`() {
        assertEquals("98765", extractOtpCode("ignore 11111 then @app.fenjoon.io #98765"))
    }

    @Test
    fun `rejects unresolved OTP placeholder`() {
        assertNull(extractOtpCode("code: %otp%"))
    }

    @Test
    fun `rejects digits embedded in a longer number`() {
        assertNull(extractOtpCode("reference: 1234567"))
    }
}
