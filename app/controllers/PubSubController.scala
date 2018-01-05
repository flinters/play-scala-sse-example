package controllers

import javax.inject.{Inject, Singleton}

import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props}
import akka.stream.actor.ActorPublisher
import akka.stream.scaladsl.Source
import controllers.ActorRefManager.{Register, UnRegister}
import controllers.PublishersManager._
import models.Message
import play.api.http.ContentTypes
import play.api.libs.EventSource
import play.api.mvc._
import play.filters.csrf.{CSRF, CSRFAddToken}

import scala.collection.mutable
import scala.concurrent.ExecutionContext

@Singleton
class PubSubController @Inject() (system: ActorSystem,
                                  cc: ControllerComponents,
                                  addToken: CSRFAddToken)
                                 (implicit executionContext: ExecutionContext)
  extends AbstractController(cc) {

  private[this] val manager = system.actorOf(PublishersManager.props, PublishersManager.name)

  def index = addToken(Action { implicit request =>
    Ok(views.html.pubSub(CSRF.getToken.get))
  })

  def receiveMessage = Action(parse.json[Message]) { request =>
    manager ! SendMessage(request.body.toString)
    Ok
  }

  def sse = Action {
    val source =
      Source
        .actorPublisher[String](Publisher.props)
        .watchTermination() { case (publisher, terminate) =>
          manager ! Register(publisher)
          terminate.onComplete(_ => manager ! UnRegister(publisher))
          publisher
        }

    Ok.chunked(source via EventSource.flow).as(ContentTypes.EVENT_STREAM)
  }

}

/**
  * @see
  *      <a href="http://doc.akka.io/docs/akka/2.5.4/scala/stream/stream-integrations.html#implementing-reactive-streams-publisher-or-subscriber">
  *        Implementing Reactive Streams Publisher or Subscriber
  *      </a>
  */
@Deprecated
class Publisher extends ActorPublisher[String] {
  import akka.stream.actor.ActorPublisherMessage._

  val MaxBufferSize = 256
  private[this] var buf = Vector.empty[String]

  def receive = {
    case SendMessage(message) if buf.size == MaxBufferSize =>
      sender() ! MessageDenied(self, message)
    case SendMessage(message) =>
      if (buf.isEmpty && totalDemand > 0)
        onNext(message)
      else {
        buf :+= message
        deliverBuf()
      }
    case Request(_) =>
      deliverBuf()
    case Cancel =>
      context.stop(self)
  }

  @annotation.tailrec
  final def deliverBuf(): Unit =
    if (totalDemand > 0) {
      /*
       * totalDemand is a Long and could be larger than
       * what buf.splitAt can accept
       */
      if (totalDemand <= Int.MaxValue) {
        val (use, keep) = buf.splitAt(totalDemand.toInt)
        buf = keep
        use foreach onNext
      } else {
        val (use, keep) = buf.splitAt(Int.MaxValue)
        buf = keep
        use foreach onNext
        deliverBuf()
      }
    }
}

@Deprecated
object Publisher {
  def props: Props = Props[Publisher]
}

class PublishersManager extends Actor with ActorLogging {

  private[this] val publishers = mutable.Set.empty[ActorRef]

  def receive = {
    case Register(publisher)   => publishers += publisher
    case UnRegister(publisher) => publishers -= publisher
    case event: SendMessage    => publishers.foreach(_ ! event)
    case denied: MessageDenied => log.error(denied.toString)
  }

}

object PublishersManager {
  val name: String = PublishersManager.getClass.getSimpleName
  val props: Props = Props[PublishersManager]

  case class SendMessage(message: String)

  case class Register(publisher: ActorRef)
  case class UnRegister(publisher: ActorRef)
  case class MessageDenied(publisher: ActorRef, message: String)
}
