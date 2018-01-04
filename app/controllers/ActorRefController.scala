package controllers

import javax.inject.{Inject, Singleton}

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.Source
import controllers.ActorRefManager._
import models.Message
import play.api.http.ContentTypes
import play.api.libs.EventSource
import play.api.mvc._
import play.filters.csrf.{CSRF, CSRFAddToken}

import scala.collection.mutable
import scala.concurrent.ExecutionContext

@Singleton
class ActorRefController @Inject() (system: ActorSystem,
                                    cc: ControllerComponents,
                                    addToken: CSRFAddToken)
                                   (implicit executionContext: ExecutionContext)
  extends AbstractController(cc) {

  private[this] val manager = system.actorOf(ActorRefManager.props)

  def index = addToken(Action { implicit request =>
    Ok(views.html.actorRef(CSRF.getToken.get))
  })

  def receiveMessage = Action(parse.json[Message]) { request =>
    manager ! SendMessage(request.body.toString)
    Ok
  }

  def sse = Action {
    val source  =
      Source
        .actorRef[String](32, OverflowStrategy.dropHead)
        .watchTermination() { case (actorRef, terminate) =>
          manager ! Register(actorRef)
          terminate.onComplete(_ => manager ! UnRegister(actorRef))
          actorRef
        }

    Ok.chunked(source via EventSource.flow).as(ContentTypes.EVENT_STREAM)
  }

}

class ActorRefManager extends Actor {

  private[this] val actors = mutable.Map.empty[String, ActorRef]

  def receive = {
    case Register(actorRef) =>
      actors += actorRef.toString() -> actorRef
    case UnRegister(actorRef) =>
      actors -= actorRef.toString()
    case SendMessage(message) =>
      actors.values.foreach(_ ! message)
  }

}

object ActorRefManager {
  def props: Props = Props[ActorRefManager]

  case class SendMessage(message: String)

  case class Register(actorRef: ActorRef)
  case class UnRegister(actorRef: ActorRef)
}
