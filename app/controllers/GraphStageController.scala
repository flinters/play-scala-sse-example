package controllers

import javax.inject.{Inject, Singleton}

import akka.actor._
import akka.stream.scaladsl.Source
import akka.stream.stage.{GraphStage, GraphStageLogic, OutHandler}
import akka.stream.{Attributes, Outlet, SourceShape}
import controllers.GraphStageManager._
import models.Message
import play.api.http.ContentTypes
import play.api.libs.EventSource
import play.api.mvc._
import play.filters.csrf.{CSRF, CSRFAddToken}

import scala.collection.mutable

@Singleton
class GraphStageController @Inject() (system: ActorSystem,
                                      cc: ControllerComponents,
                                      addToken: CSRFAddToken)
  extends AbstractController(cc) {

  private[this] val manager = system.actorOf(GraphStageManager.props)

  def index() = addToken(Action { implicit request =>
    Ok(views.html.graphStage(CSRF.getToken.get))
  })

  def receiveMessage() = Action(parse.json[Message]) { request =>
    manager ! SendMessage(request.body.toString)
    Ok
  }

  def sse() = Action {
    val source = Source.fromGraph(new MessageStage(manager))
    Ok.chunked(source via EventSource.flow).as(ContentTypes.EVENT_STREAM)
  }

}

class GraphStageManager extends Actor {

  private[this] val actors = mutable.Map.empty[String, ActorRef]

  def receive = {
    case Register(actorRef) =>
      context.watch(actorRef)
      actors += actorRef.toString() -> actorRef
    case Terminated(actorRef) =>
      context.unwatch(actorRef)
      actors -= actorRef.toString()
    case message: SendMessage =>
      actors.values.foreach(_ ! message)
  }

}

object GraphStageManager {
  def props: Props = Props[GraphStageManager]

  case class SendMessage(message: String)

  case class Register(actorRef: ActorRef)
}

class MessageStage(manager: ActorRef) extends GraphStage[SourceShape[String]] {
  // Define the (sole) output port of this stage
  val out: Outlet[String] = Outlet("MessageStage")
  // Define the shape of this stage, which is SourceShape with the port we defined above
  val shape: SourceShape[String] = SourceShape(out)

  private[this] val messages = mutable.Queue.empty[String]
  private[this] var downstreamWaiting = false

  // This is where the actual (possibly stateful) logic will live
  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    implicit def self: ActorRef = stageActor.ref

    override def preStart(): Unit = {
      val thisStageActor = getStageActor(receive).ref
      manager ! Register(thisStageActor)
    }

    setHandler(out, new OutHandler {
      override def onPull(): Unit = {
        if (messages.isEmpty) {
          downstreamWaiting = true
        } else {
          push(out, messages.dequeue())
        }
      }
    })

    private def receive(receive: (ActorRef, Any)): Unit = receive match {
      case (_, SendMessage(message)) =>
        messages.enqueue(message)
        if (downstreamWaiting) {
          downstreamWaiting = false
          push(out, messages.dequeue())
        }
    }
  }
}
