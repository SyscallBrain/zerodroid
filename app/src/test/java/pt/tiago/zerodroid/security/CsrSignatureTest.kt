package pt.tiago.zerodroid.security

import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

class CsrSignatureTest {
    @Test
    fun generatedCsrHasValidSelfSignature() {
        val g = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        val csrPem = buildCsrPem(g.generateKeyPair())
        val req = org.bouncycastle.pkcs.PKCS10CertificationRequest(
            java.util.Base64.getMimeDecoder().decode(
                csrPem.substringAfter("-----BEGIN CERTIFICATE REQUEST-----")
                    .substringBefore("-----END CERTIFICATE REQUEST-----")
            )
        )
        assertTrue("CSR signature invalid", req.isSignatureValid(
            org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder().build(req.subjectPublicKeyInfo)
        ))
    }
}
