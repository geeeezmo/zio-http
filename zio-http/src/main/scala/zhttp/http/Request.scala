package zhttp.http

import io.netty.buffer.{ByteBuf, Unpooled}
import zhttp.http.headers.HeaderExtension
import zhttp.service.HTTP_CONTENT_HANDLER
import zhttp.service.server.content.handlers.UnsafeRequestHandler.{UnsafeChannel, UnsafeContent}
import zio.stream.ZStream
import zio.{Chunk, IO, Queue, Task, UIO, ZIO}

import java.net.InetAddress
import scala.annotation.unused

trait Request extends HeaderExtension[Request] { self =>

  /**
   * Updates the headers using the provided function
   */
  final override def updateHeaders(update: Headers => Headers): Request = self.copy(headers = update(self.headers))

  def copy(method: Method = self.method, url: URL = self.url, headers: Headers = self.headers): Request = {
    val m = method
    val u = url
    val h = headers
    new Request {
      override def method: Method                     = m
      override def url: URL                           = u
      override def headers: Headers                   = h
      override def remoteAddress: Option[InetAddress] = self.remoteAddress
      override def unsafeBody(
        msg: (
          UnsafeChannel,
          UnsafeContent,
        ) => Unit,
      ): Unit = self.unsafeBody(msg)
    }
  }

  def unsafeBody(msg: (UnsafeChannel, UnsafeContent) => Unit): Unit

  /**
   * Decodes the content of request as a Chunk of Bytes
   */
  def body[R]: ZStream[R, Throwable, ByteBuf] = ???
//    for {
//      raw    <- ZStream.fromEffect(???)
//      stream <- ZStream.fromQueue(raw)
//    } yield stream

  def getBodyAsStream: ZStream[Any, Throwable, ByteBuf] =
    ZStream
      .effectAsync[Any, Throwable, ByteBuf](cb =>
        self.unsafeBody((ch, msg) => {

          if (msg.isLast) {
            // ch.ctx.pipeline().remove(HTTP_CONTENT_HANDLER)
            //  cb(IO.fail(None))
          } else {
            ch.read()
            ()
          }
          cb(IO.succeed(Chunk(msg.content.content())))
        }),
      )

  /**
   * Decodes the content of request as string
   */
  def bodyAsString: Task[String] = {
    for {
      buffer <- UIO(Unpooled.compositeBuffer())
      body   <- ZIO.effectAsync[Any, Throwable, String](cb =>
        self.unsafeBody((ch, msg) => {
          buffer.writeBytes(msg.content.content())
          if (msg.isLast) {
            ch.ctx.pipeline().remove(HTTP_CONTENT_HANDLER)
            cb(UIO(buffer.toString(HTTP_CHARSET)))
          } else {
            ch.read()
            ()
          }
        }),
      )
    } yield body

  }

  /**
   * Gets all the headers in the Request
   */
  def headers: Headers

  /**
   * Checks is the request is a pre-flight request or not
   */
  def isPreflight: Boolean = method == Method.OPTIONS

  /**
   * Gets the request's method
   */
  def method: Method

  /**
   * Gets the request's path
   */
  def path: Path = url.path

  /**
   * Gets the remote address if available
   */
  def remoteAddress: Option[InetAddress]

  /**
   * Overwrites the method in the request
   */
  def setMethod(method: Method): Request = self.copy(method = method)

  /**
   * Overwrites the path in the request
   */
  def setPath(path: Path): Request = self.copy(url = self.url.copy(path = path))

  /**
   * Overwrites the url in the request
   */
  def setUrl(url: URL): Request = self.copy(url = url)

  /**
   * Gets the complete url
   */
  def url: URL

  private[zhttp] def bodyAsByteBuf: Task[ByteBuf]
}

object Request {

  /**
   * Constructor for Request
   */
  def apply(
    method: Method = Method.GET,
    url: URL = URL.root,
    headers: Headers = Headers.empty,
    remoteAddress: Option[InetAddress] = None,
    @unused data: HttpData = HttpData.Empty,
  ): Request = {
    val m  = method
    val u  = url
    val h  = headers
    val ra = remoteAddress
    new Request {
      override def method: Method                              = m
      override def url: URL                                    = u
      override def headers: Headers                            = h
      override def remoteAddress: Option[InetAddress]          = ra
      override private[zhttp] def bodyAsByteBuf: Task[ByteBuf] = data.toByteBuf
      override def unsafeBody(
        msg: (
          UnsafeChannel,
          UnsafeContent,
        ) => Unit,
      ): Unit = ()
    }
  }

  /**
   * Effectfully create a new Request object
   */
  def make[E <: Throwable](
    method: Method = Method.GET,
    url: URL = URL.root,
    headers: Headers = Headers.empty,
    remoteAddress: Option[InetAddress],
    content: HttpData = HttpData.empty,
  ): UIO[Request] =
    UIO(Request(method, url, headers, remoteAddress, content))

  /**
   * Lift request to TypedRequest with option to extract params
   */
  final class ParameterizedRequest[A](req: Request, val params: A) extends Request {
    override def getHeaders: Headers                = req.getHeaders
    override def method: Method                     = req.method
    override def remoteAddress: Option[InetAddress] = req.remoteAddress
    override def url: URL                           = req.url
    override def unsafeBody(
      msg: (
        UnsafeChannel,
        UnsafeContent,
      ) => Unit,
    ): Unit = req.unsafeBody(msg)
  }

  object ParameterizedRequest {
    def apply[A](req: Request, params: A): ParameterizedRequest[A] = new ParameterizedRequest(req, params)
  }
}
