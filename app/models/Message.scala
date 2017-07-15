package models

import play.api.libs.json._
import play.api.libs.json.Reads._
import play.api.libs.functional.syntax._

case class Message(
  name: Option[String],
  message: String,
  timestamp: Long
) {
  override def toString: String = Json.toJson(this).toString()
}

object Message {

  implicit val messageFormat: Format[Message] = (
    (JsPath \ 'name     ).formatNullable[String] and
    (JsPath \ 'message  ).format[String] and
    (JsPath \ 'timestamp).format[Long]
  )(Message.apply, unlift(Message.unapply))

}
