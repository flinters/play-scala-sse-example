package controllers

import javax.inject.{Inject, Singleton}

import play.api.mvc._

@Singleton
class HomeController @Inject() (cc: ControllerComponents)
  extends AbstractController(cc) {

  def index() = Action {
    Ok(views.html.index())
  }

}
