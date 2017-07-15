package controllers

import javax.inject.{Inject, Singleton}

import akka.stream.Materializer
import akka.stream.scaladsl.{BroadcastHub, Keep, MergeHub, Source}
import models.Message
import play.api.http.ContentTypes
import play.api.libs.EventSource
import play.api.mvc._

@Singleton
class HubController @Inject() (implicit val mat: Materializer) extends Controller {

  private[this] val (sink, source) =
    MergeHub.source[String](perProducerBufferSize = 16)
      .toMat(BroadcastHub.sink(bufferSize = 256))(Keep.both)
      .run()

  def index = Action {
    Ok(views.html.hub())
  }

  def receiveMessage = Action(BodyParsers.parse.json[Message]) { request =>
    Source.single(request.body.toString).runWith(sink)
    Ok
  }

  def sse = Action {
    Ok.chunked(source via EventSource.flow).as(ContentTypes.EVENT_STREAM)
  }

}
