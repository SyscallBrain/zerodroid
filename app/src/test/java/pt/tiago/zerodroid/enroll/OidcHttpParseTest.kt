package pt.tiago.zerodroid.enroll

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OidcHttpParseTest {
    @Test
    fun pendingAndSlowDown() {
        assertEquals(PollOutcome.Pending, parsePollResponse(200, """{"status":"pending"}""", null))
        assertEquals(PollOutcome.SlowDown, parsePollResponse(200, """{"status":"slow_down"}""", null))
    }

    @Test
    fun granted() = assertEquals(
        PollOutcome.Granted("at", 3600),
        parsePollResponse(200, """{"status":"granted","provider":"oidc.corp","token":{"access_token":"at","expires_in":3600}}""", null),
    )

    @Test
    fun grantedWithoutExpiry() =
        assertEquals(PollOutcome.Granted("at", null), parsePollResponse(200, """{"status":"granted","token":{"access_token":"at"}}""", null))

    @Test
    fun forbiddenIsDenied() = assertEquals(
        PollOutcome.Denied("device grant failed: access_denied"),
        parsePollResponse(403, """{"error":"device grant failed: access_denied"}""", null),
    )

    @Test
    fun badGatewayRetries() = assertTrue(parsePollResponse(502, """{"error":"x"}""", null) is PollOutcome.Retry)

    @Test
    fun rateLimited() = assertEquals(PollOutcome.RateLimited(30), parsePollResponse(429, "{}", 30))

    @Test(expected = EnrollHttpException::class)
    fun unexpectedStatusThrows() { parsePollResponse(500, "{}", null) }

    @Test(expected = EnrollHttpException::class)
    fun grantedWithoutTokenThrows() { parsePollResponse(200, """{"status":"granted"}""", null) }

    @Test
    fun grantedToStringRedacts() = assertFalse(PollOutcome.Granted("secret", 1).toString().contains("secret"))

    @Test
    fun deviceStartToStringRedacts() =
        assertFalse(DeviceStart("dc-secret", "ABCD-EFGH", "https://idp/device", null, 600).toString().contains("dc-secret"))

    @Test
    fun deviceStartParsesWithDefaultInterval() {
        val s = parseDeviceStart("""{"device_code":"d","user_code":"U","verification_uri":"https://i/d","expires_in":600}""")
        assertEquals(5L, s.interval)
        assertEquals("U", s.userCode)
    }

    @Test
    fun serviceUnavailableIsRetryable() = assertEquals(PollOutcome.RateLimited(7), parsePollResponse(503, """{"error":"busy"}""", 7))
}
