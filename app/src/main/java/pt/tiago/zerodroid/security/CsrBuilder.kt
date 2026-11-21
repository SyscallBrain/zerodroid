package pt.tiago.zerodroid.security

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.pkcs.PKCS10CertificationRequestBuilder
import org.bouncycastle.util.io.pem.PemObject
import org.bouncycastle.util.io.pem.PemWriter
import java.io.StringWriter
import java.security.KeyPair

/** Builds a PKCS#10 CSR PEM signed by the Keystore-held EC key. */
fun buildCsrPem(keyPair: KeyPair, commonName: String = "zerodroid"): String {
    val signer = JcaContentSignerBuilder("SHA256withECDSA").build(keyPair.private)
    val request = PKCS10CertificationRequestBuilder(
        X500Name("CN=$commonName"),
        org.bouncycastle.asn1.x509.SubjectPublicKeyInfo.getInstance(keyPair.public.encoded),
    ).build(signer)
    return StringWriter().use { out ->
        PemWriter(out).use { writer ->
            writer.writeObject(PemObject("CERTIFICATE REQUEST", request.encoded))
        }
        out.toString()
    }
}
