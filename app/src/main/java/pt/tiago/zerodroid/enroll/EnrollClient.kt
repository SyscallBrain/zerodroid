package pt.tiago.zerodroid.enroll

import pt.tiago.zerodroid.security.PairingPayload
import pt.tiago.zerodroid.security.caFingerprint
import pt.tiago.zerodroid.security.enrollmentSas

data class VerifiedCa(val caPem: String, val sas: String)

class EnrollClient(private val http: EnrollHttp, private val csr: () -> String) {
    var onCsr: (String) -> Unit = {}
    suspend fun fetchAndVerify(p: PairingPayload): Result<VerifiedCa> = runCatching {
        val caPem = http.fetchCa(p.host, p.enrollPort)
        val fp = caFingerprint(caPem)
        require(fp == p.caFp) { "CA fingerprint does not match the pairing link" }
        VerifiedCa(caPem, enrollmentSas(p.enrollCode, fp))
    }

    suspend fun enroll(p: PairingPayload, ca: VerifiedCa): Result<EnrollResponse> = runCatching {
        val csrPem = csr()
        onCsr(csrPem)
        val response = http.postEnroll(p.host, p.enrollPort, ca.caPem, p.enrollCode, csrPem)
        require(caFingerprint(response.caChainPem) == caFingerprint(ca.caPem)) {
            "response CA fingerprint does not match the verified CA"
        }
        response
    }
}
