package pt.tiago.zerodroid.security

import org.junit.Test

class PemDebugTest {
    @Test
    fun debug() {
        val pem = javaClass.getResourceAsStream("/fixtures/daemon-ca.pem")!!.bufferedReader().use { it.readText() }
        val body = pem.split("-----BEGIN CERTIFICATE-----")
            .drop(1).map { it.substringBefore("-----END CERTIFICATE-----") }
            .map { it.filter { c -> !c.isWhitespace() } }
        println("parts=${body.size} len=${body[0].length} head=${body[0].take(20)} tail=${body[0].takeLast(20)}")
    }
}
