package controllers

import akka.actor._
import concurrent.Future
import spray.routing.HttpService
import services.Spitball
import spray.http.{HttpResponse, StatusCodes}

class WebActor extends Actor with Web {
  def actorRefFactory = context

  def receive = runRoute(route)
}

trait Web extends HttpService with Handlers with CORSDirectives {

  val route = pathPrefix("v1") {
    post {
      path("drain") {
        entity(as[String]) {
          body =>
            Future(Spitball().drain(body))
            complete(HttpResponse(StatusCodes.Accepted))
        }
      }
    } ~
      get {
        path("requests" / Rest) {
          requestId =>
            complete(Future(Spitball().getV1(requestId)))
        }
      }
  } ~
    pathPrefix("v2") {
      get {
        path("requests" / Rest) {
          requestId =>
            complete(Future(Spitball().get(requestId)))
        }
      }
    }

}
