package extension.io

import extension.logging.{ILogger, Log}
import play.api.libs.json.{Json, JsValue}
import sun.net.ConnectionResetException

import java.net.URI
import java.net.http.{HttpClient, WebSocket => RawWebSocket}
import java.nio.ByteBuffer
import java.util.concurrent.{CompletableFuture, CompletionStage}
import java.util.zip.GZIPInputStream
import scala.collection.{immutable, mutable}

trait WebSocketDecoder[T] {
  def apply(bb: ByteBuffer): T = ???
  def apply(s:  String):     T = ???
}

abstract class WebSocket[T](
    uri:           URI,
    builder:       RawWebSocket.Builder,
    decode:        WebSocketDecoder[T],
    autoReconnect: Boolean
) extends Subscriber[T]
    with ILogger {

  self =>

  def this(
      uri:           URI,
      subprotocols:  List[String]           = List(),
      headers:       List[(String, String)] = List(),
      autoReconnect: Boolean = true
  )(implicit decode: WebSocketDecoder[T], client: HttpClient = HttpClient.newBuilder().build()) {
    this(uri, client.newWebSocketBuilder(), decode, autoReconnect)
    if (subprotocols.nonEmpty) {
      builder.subprotocols(subprotocols.head, subprotocols.tail: _*)
    }
    headers.foreach((builder.header _).tupled)
  }

  val rawListener: RawWebSocket.Listener = new RawWebSocket.Listener {
    private final val bufs = mutable.ListBuffer[ByteBuffer]()
    private final val sb   = new StringBuilder(128)

    override def onBinary(ws: RawWebSocket, buf: ByteBuffer, last: Boolean): CompletionStage[_] = {
      val ret = super.onBinary(ws, buf, last)
      bufs += buf
      if (last) {
        val l      = bufs.foldLeft(0)(_ + _.remaining())
        val gather = ByteBuffer.allocate(l)
        bufs.foreach(gather.put)
        bufs.clear()
        self.onMessage(decode(gather))
      }
      ret
    }

    override def onText(ws: RawWebSocket, cs: CharSequence, last: Boolean): CompletionStage[_] = {
      val ret = super.onText(ws, cs, last)
      sb.append(cs)
      if (last) {
        self.onMessage(decode(sb.toString()))
        sb.clear()
      }
      ret
    }

    override def onClose(ws: RawWebSocket, code: Int, reason: String): CompletionStage[_] = {
      val ret = super.onClose(ws, code, reason)
      self.onClose(code, reason)
      ret
    }

    override def onError(ws: RawWebSocket, e: Throwable): Unit = {
      val ret = super.onError(ws, e)
      self.onError(e)
      ret
    }

    override def onOpen(webSocket: RawWebSocket): Unit = {
      super.onOpen(webSocket)
      self.onOpen()
    }

    override def onPing(webSocket: RawWebSocket, message: ByteBuffer): CompletionStage[_] = {
      val ret = super.onPing(webSocket, message)
      self.onPing(message)
      ret
    }

    override def onPong(webSocket: RawWebSocket, message: ByteBuffer): CompletionStage[_] = {
      val ret = super.onPong(webSocket, message)
      self.onPong(message)
      ret
    }

  }

  def send(msg: T): Unit =
    connection.sendText(msg.toString, true)

  def onMessage(msg: T): Unit = {
    consume(msg)
  }

  def onClose(code: Int, reason: String): Unit = {
    if (code == RawWebSocket.NORMAL_CLOSURE) {
      log.warn(s"WebSocket $uri normal_closed $code: $reason")
      return
    }
    if (autoReconnect) {
      reconnect(code, reason)
    }
  }
  def onPing(buf: ByteBuffer): Unit = {}
  def onPong(buf: ByteBuffer): Unit = {}
  def onError(e: Throwable): Unit = {
    log.error(e)(uri.toString)
    e match {
      case _: ConnectionResetException => reconnect(-1, e.getMessage)
      case _ => throw e
    }
  }
  def onOpen(): Unit = {
    val f = fConnection
    f.thenAccept { conn =>
      if (f == fConnection)
        connnectedHandlers.values.foreach(_(conn))
    }
  }
  var fConnection = connect()
  def connection: RawWebSocket = fConnection.get()

  private def connect(): CompletableFuture[RawWebSocket] = {
    builder
      .buildAsync(uri, rawListener)
      .exceptionally { e => Log.error(e)("WebSocket Connection Error:"); null }
  }

  def reconnect(code: Int, reason: String = ""): Unit = {
    log.warn(s"WebSocket reconnect $uri, cause $code: $reason")
    this.synchronized {
      if (connection != null) {
        if (!connection.isInputClosed) {
          connection.sendClose(RawWebSocket.NORMAL_CLOSURE, "")
        }
        connection.abort()
      }
      this.fConnection = connect()
    }
  }

  implicit final class EventHandler(f: () => Unit) {
    @volatile var conn: RawWebSocket = null
    def apply(ws: RawWebSocket): Unit =
      this.synchronized {
        if (this.conn == ws) return
        this.conn = ws
        f()
      }
  }

  @volatile var connnectedHandlers = immutable.HashMap[String, EventHandler]()
  def onConnected(key: String)(f: () => Unit): Unit = {
    connnectedHandlers += key -> f
    fConnection.thenAccept { conn =>
      connnectedHandlers.get(key).map(_(conn))
    }
  }

  def removeConnectedHandler(key: String): Unit = {
    connnectedHandlers -= key
  }
}

object WebSocket {
  implicit def toRawWebSocket[T](ws: WebSocket[T]): RawWebSocket = ws.connection
  implicit object JsValueDecode extends WebSocketDecoder[JsValue] {
    override def apply(bb: ByteBuffer): JsValue = Json.parse(bb.inputStream)

    override def apply(s: String): JsValue = Json.parse(s)
  }

  implicit object JsValueDecodeWithGZIP extends WebSocketDecoder[JsValue] {
    override def apply(bb: ByteBuffer): JsValue = Json.parse(new GZIPInputStream(bb.inputStream))

    override def apply(s: String): JsValue = Json.parse(s)
  }
}
