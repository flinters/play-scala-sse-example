package controllers

import javax.inject.Singleton

import play.api.mvc._

@Singleton
class HomeController extends Controller {

  def index = Action {
    Ok(views.html.index())
  }

}
