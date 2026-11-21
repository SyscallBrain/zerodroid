package pt.tiago.zerodroid.security

data class PairingPayload(
    val host: String,
    val wssPort: Int,
    val enrollPort: Int,
    val enrollCode: String,
    val token: String,
    val caFp: String,
    val name: String,
) {
    override fun toString(): String =
        "PairingPayload(name=$name, host=$host, wssPort=$wssPort, enrollPort=$enrollPort, " +
            "enrollCode=***, token=***, caFp=$caFp)"

    companion object {
        fun parse(input: String): Result<PairingPayload> = runCatching {
            require(input.isNotBlank()) { "empty pairing link" }
            val raw = if (input.contains("://")) {
                require(input.startsWith("zerodroid://pair?", ignoreCase = true)) {
                    "unsupported pairing link scheme"
                }
                input.substringAfter('?')
            } else {
                input
            }
            val params = raw.split('&').associate { part ->
                val key = part.substringBefore('=')
                val value = java.net.URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
                key to value
            }
            require(params["v"] == "1") { "unsupported pairing link version" }
            fun req(key: String) = requireNotNull(params[key]) { "missing $key" }
            val host = req("host")
            require(host.isNotBlank()) { "missing host" }
            val wss = req("wss").toIntOrNull()?.takeIf { it in 1..65535 }
                ?: throw IllegalArgumentException("invalid wss port")
            val enroll = req("enroll").toIntOrNull()?.takeIf { it in 1..65535 }
                ?: throw IllegalArgumentException("invalid enroll port")
            val fp = req("ca_fp").lowercase()
            require(fp.length == 64 && fp.all { it in '0'..'9' || it in 'a'..'f' }) {
                "invalid ca fingerprint"
            }
            PairingPayload(
                host = host,
                wssPort = wss,
                enrollPort = enroll,
                enrollCode = req("code"),
                token = req("token"),
                caFp = fp,
                name = params["name"]?.takeIf { it.isNotBlank() } ?: "daemon",
            )
        }
    }
}
