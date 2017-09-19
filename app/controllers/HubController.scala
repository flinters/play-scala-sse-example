package controllers

import javax.inject.{Inject, Singleton}

import akka.stream.Materializer
import akka.stream.scaladsl.{BroadcastHub, Keep, MergeHub, Source}
import models.Message
import play.api.http.ContentTypes
import play.api.libs.EventSource
import play.api.mvc._
import play.filters.csrf.{CSRF, CSRFAddToken}

@Singleton
class HubController @Inject() (cc: ControllerComponents,
                               addToken: CSRFAddToken)
                              (implicit val mat: Materializer)
  extends AbstractController(cc) {

  private[this] val (sink, source) =
    MergeHub.source[String](perProducerBufferSize = 16)
      .toMat(BroadcastHub.sink(bufferSize = 256))(Keep.both)
      .run()

  def index() = addToken(Action { implicit request =>
    Ok(views.html.hub(CSRF.getToken.get))
  })

  def receiveMessage() = Action(parse.json[Message]) { request =>
    Source.single(request.body.toString).runWith(sink)
    Ok
  }

  def sse() = Action {
    Ok.chunked(source via EventSource.flow).as(ContentTypes.EVENT_STREAM)
  }

}
