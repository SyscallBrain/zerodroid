package pt.tiago.zerodroid.rpc

interface WsListener {
    fun onOpen()
    fun onText(text: String)
    fun onClosed(code: Int, reason: String)
    fun onFailure(t: Throwable)
}

interface WsHandle {
    fun send(text: String): Boolean
    fun close(code: Int, reason: String)
}

interface WsTransport {
    fun open(url: String, listener: WsListener): WsHandle
}
