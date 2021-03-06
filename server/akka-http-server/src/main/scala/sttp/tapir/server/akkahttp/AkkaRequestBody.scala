package sttp.tapir.server.akkahttp

import akka.http.scaladsl.model.{HttpEntity, Multipart}
import akka.http.scaladsl.server.RequestContext
import akka.http.scaladsl.unmarshalling.FromEntityUnmarshaller
import akka.stream.Materializer
import akka.stream.scaladsl.{FileIO, Sink}
import akka.util.ByteString
import sttp.capabilities.akka.AkkaStreams
import sttp.model.{Header, Part}
import sttp.tapir.model.ServerRequest
import sttp.tapir.server.interpreter.RequestBody
import sttp.tapir.{RawBodyType, RawPart}

import java.io.ByteArrayInputStream
import scala.concurrent.{ExecutionContext, Future}

private[akkahttp] class AkkaRequestBody(ctx: RequestContext, request: ServerRequest, serverOptions: AkkaHttpServerOptions)(implicit
    mat: Materializer,
    ec: ExecutionContext
) extends RequestBody[Future, AkkaStreams] {
  override val streams: AkkaStreams = AkkaStreams
  override def toRaw[R](bodyType: RawBodyType[R]): Future[R] = toRawFromEntity(ctx.request.entity, bodyType)
  override def toStream(): streams.BinaryStream = ctx.request.entity.dataBytes

  private def toRawFromEntity[R](body: HttpEntity, bodyType: RawBodyType[R]): Future[R] = {
    bodyType match {
      case RawBodyType.StringBody(_)   => implicitly[FromEntityUnmarshaller[String]].apply(body)
      case RawBodyType.ByteArrayBody   => implicitly[FromEntityUnmarshaller[Array[Byte]]].apply(body)
      case RawBodyType.ByteBufferBody  => implicitly[FromEntityUnmarshaller[ByteString]].apply(body).map(_.asByteBuffer)
      case RawBodyType.InputStreamBody => implicitly[FromEntityUnmarshaller[Array[Byte]]].apply(body).map(new ByteArrayInputStream(_))
      case RawBodyType.FileBody =>
        serverOptions
          .createFile(request)
          .flatMap(file => body.dataBytes.runWith(FileIO.toPath(file.toPath)).map(_ => file))
      case m: RawBodyType.MultipartBody =>
        implicitly[FromEntityUnmarshaller[Multipart.FormData]].apply(body).flatMap { fd =>
          fd.parts
            .mapConcat(part => m.partType(part.name).map((part, _)).toList)
            .mapAsync[RawPart](1) { case (part, codecMeta) => toRawPart(part, codecMeta) }
            .runWith[Future[scala.collection.immutable.Seq[RawPart]]](Sink.seq)
            .asInstanceOf[Future[R]]
        }
    }
  }

  private def toRawPart[R](part: Multipart.FormData.BodyPart, bodyType: RawBodyType[R]): Future[Part[R]] = {
    toRawFromEntity(part.entity, bodyType)
      .map(r =>
        Part(
          part.name,
          r,
          otherDispositionParams = part.additionalDispositionParams,
          headers = part.additionalHeaders.map(h => Header(h.name, h.value))
        ).contentType(part.entity.contentType.toString())
      )
  }
}
