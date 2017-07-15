package controllers

import javax.inject.{Inject, Singleton}

import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props}
import akka.stream.actor.ActorPublisher
import akka.stream.scaladsl.Source
import controllers.PublishersManager._
import models.Message
import play.api.http.ContentTypes
import play.api.libs.EventSource
import play.api.mvc._

import scala.collection.mutable

@Singleton
class PubSubController @Inject() (system: ActorSystem) extends Controller {

  private[this] val manager = system.actorOf(PublishersManager.props, PublishersManager.name)

  def index = Action {
    Ok(views.html.pubSub())
  }

  def receiveMessage = Action(BodyParsers.parse.json[Message]) { request =>
    manager ! SendMessage(request.body.toString)
    Ok
  }

  def sse = Action {
    val source = Source.actorPublisher[String](Publisher.props)
    Ok.chunked(source via EventSource.flow).as(ContentTypes.EVENT_STREAM)
  }

}

/**
  * @see
  *      <a href="http://doc.akka.io/docs/akka/2.4.18/scala/stream/stream-integrations.html#Implementing_Reactive_Streams_Publisher_or_Subscriber">
  *        Implementing Reactive Streams Publisher or Subscriber
  *      </a>
  */
class Publisher extends ActorPublisher[String] {
  import akka.stream.actor.ActorPublisherMessage._

  val MaxBufferSize = 256
  private[this] var buf = Vector.empty[String]
  private[this] val publishersManager = context.actorSelection("/user/" + PublishersManager.name)

  publishersManager ! Register(self)

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
      publishersManager ! UnRegister(self)
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

object Publisher {
  def props: Props = Props[Publisher]
}

class PublishersManager extends Actor with ActorLogging {

  private[this] val publishers = mutable.Map.empty[String, ActorRef]

  def receive = {
    case Register(publisher) =>
      publishers += publisher.toString -> publisher
    case UnRegister(publisher) =>
      publishers -= publisher.toString
    case event: SendMessage =>
      publishers.values.foreach(_ ! event)
    case denied: MessageDenied =>
      log.error(denied.toString)
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
