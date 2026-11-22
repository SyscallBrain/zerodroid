package pt.tiago.zerodroid.enroll

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.zerodroid.enroll.OidcTargetException.Field

class OidcTargetTest {
    private fun field(r: Result<OidcTarget>) = (r.exceptionOrNull() as OidcTargetException).field

    @Test
    fun bareHostGetsDefaults() {
        val t = parseOidcTarget("daemon.example.com", "corp").getOrThrow()
        assertEquals("wss://daemon.example.com:9781/", t.wssUrl)
        assertEquals(9781, t.wssPort)
        assertEquals("daemon.example.com", t.host)
        assertEquals("https://daemon.example.com:42617", t.gatewayUrl)
        assertEquals("corp", t.alias)
        assertEquals("oidc.corp", t.provider)
    }

    @Test
    fun aliasPrefixAndWhitespaceStripped() =
        assertEquals("corp", parseOidcTarget(" h ", " oidc.corp ").getOrThrow().alias)

    @Test
    fun hostWithPortKeepsPort() =
        assertEquals("wss://h:9000/", parseOidcTarget("h:9000", "c").getOrThrow().wssUrl)

    @Test
    fun wssUrlWithoutPortMeans443() {
        val t = parseOidcTarget("h", "c", wssUrl = "wss://h/zc/").getOrThrow()
        assertEquals(443, t.wssPort)
        assertEquals("wss://h/zc/", t.wssUrl)
    }

    @Test
    fun gatewayPathPrefixKept() = assertEquals(
        "https://gw.example.com/zc",
        parseOidcTarget("h", "c", gatewayUrl = "https://gw.example.com/zc/").getOrThrow().gatewayUrl,
    )

    @Test
    fun blankOptionalFieldsUseDefaults() {
        val t = parseOidcTarget("h", "c", wssUrl = " ", gatewayUrl = "").getOrThrow()
        assertEquals("wss://h:9781/", t.wssUrl)
        assertEquals("https://h:42617", t.gatewayUrl)
    }

    @Test
    fun httpGatewayOnlyOnLoopback() {
        assertTrue(parseOidcTarget("127.0.0.1", "c", gatewayUrl = "http://127.0.0.1:42617").isSuccess)
        assertTrue(parseOidcTarget("localhost", "c", gatewayUrl = "http://localhost:42617").isSuccess)
        assertEquals(Field.GatewayUrl, field(parseOidcTarget("h", "c", gatewayUrl = "http://h:42617")))
        assertEquals(Field.GatewayUrl, field(parseOidcTarget("h", "c", gatewayUrl = "ftp://h")))
    }

    @Test
    fun badAliasRejected() {
        listOf("", "-corp", "co rp", "a".repeat(65), "oidc.").forEach {
            assertEquals(it, Field.Alias, field(parseOidcTarget("h", it)))
        }
    }

    @Test
    fun badHostRejected() {
        listOf("", "  ", "h/x", "u@h").forEach { assertEquals(it, Field.Host, field(parseOidcTarget(it, "c"))) }
    }

    @Test
    fun wssUrlWithQueryOrCredentialsRejected() {
        listOf("wss://u:p@h/", "wss://h/?x=1", "https://h/", "wss://h/#f").forEach {
            assertEquals(it, Field.WssUrl, field(parseOidcTarget("h", "c", wssUrl = it)))
        }
    }

    @Test
    fun wssUrlForAnotherHostIsRejected() {
        assertEquals(Field.WssUrl, field(parseOidcTarget("daemon.example.com", "c", wssUrl = "wss://other.example.com:9781/")))
        assertTrue(parseOidcTarget("DAEMON.example.com", "c", wssUrl = "wss://daemon.example.com/zc/").isSuccess)
    }
}
