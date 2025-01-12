package org.seekloud.theia.distributor

import org.seekloud.theia.distributor.http.HttpService
import org.seekloud.theia.distributor.common.AppSettings._
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.{ActorRef, DispatcherSelector}
import akka.actor.{ActorSystem, Scheduler}
import akka.dispatch.MessageDispatcher
import akka.event.{Logging, LoggingAdapter}
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import akka.util.Timeout

import scala.language.postfixOps
import org.seekloud.theia.distributor.core.{EncodeManager, LiveManager, PullActor, SaveManager, KillFFActor}
import org.seekloud.theia.rtpClient.Protocol._

/**
  * User: yuwei
  * Date: 7/15/2019
  */

object Boot extends HttpService {

  import concurrent.duration._

  override implicit val system: ActorSystem = ActorSystem("dispatcher", config)
  // the executor should not be the default dispatcher.
  override implicit val executor: MessageDispatcher = system.dispatchers.lookup("akka.actor.default-dispatcher")
  override implicit val materializer: ActorMaterializer = ActorMaterializer()

  override implicit val scheduler: Scheduler = system.scheduler

  override implicit val timeout: Timeout = Timeout(20 seconds) // for actor asks

  val blockingDispatcher: DispatcherSelector = DispatcherSelector.fromConfig("akka.actor.default-dispatcher")

  val log: LoggingAdapter = Logging(system, getClass)

  val encodeManager:ActorRef[EncodeManager.Command] = system.spawn(EncodeManager.create(),"encodeManager")

  val saveManager:ActorRef[SaveManager.Command] = system.spawn(SaveManager.create(), "saveManager")

//  val distributor:ActorRef[DistributorWorker.Command] = system.spawn(DistributorWorker.create(), "router")

//  val revActor:ActorRef[RevActor.Command] = system.spawn(RevActor.create(), "recActor")

  val liveManager:ActorRef[LiveManager.Command] = system.spawn(LiveManager.create(), "liveManager")

  val pullActor:ActorRef[Command] = system.spawn(PullActor.create(),"pullActor")

  val killFFActor:ActorRef[KillFFActor.Command] = system.spawn(KillFFActor.create(), "KillFFActor")

	def main(args: Array[String]) {
    Http().bindAndHandle(routes, httpInterface, httpPort)
    log.info(s"Listen to the $httpInterface:$httpPort")
    log.info("Done.")
    Thread.sleep(2000)

  }

}
