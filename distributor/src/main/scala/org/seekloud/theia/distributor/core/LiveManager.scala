package org.seekloud.theia.distributor.core

import java.io.File

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{Behaviors, StashBuffer, TimerScheduler}
import org.bytedeco.javacpp.Loader
import org.slf4j.LoggerFactory
import org.seekloud.theia.distributor.Boot.{encodeManager, pullActor,killFFActor,liveManager}
import org.seekloud.theia.distributor.common.Constants
import org.seekloud.theia.protocol.ptcl.distributor2Manager.DistributorProtocol._
import org.seekloud.theia.distributor.common.AppSettings.{fileLocation, indexPath}
import scala.concurrent.duration._
import scala.collection.mutable

/**
  * Author: tldq
  * Date: 2019-10-21
  * Time: 18:20
  */
object LiveManager {
  private val log = LoggerFactory.getLogger(this.getClass)

  sealed trait Command

  case class updateRoom( roomId:Long, liveId:String, startTime: Long) extends Command

  case object KillFF extends Command

  case class liveStop( liveId:String) extends Command

  case class CheckLive(liveId:String, reply:ActorRef[CheckStreamRsp]) extends Command

  case class RoomWithPort(roomId: Long, port: Int) extends Command

  case class GetAllLiveInfo(reply: ActorRef[GetTransLiveInfoRsp]) extends  Command

  case class CheckStream(liveId:String) extends Command


  def create(): Behavior[Command] = {
    Behaviors.setup[Command] { ctx =>
      implicit val stashBuffer: StashBuffer[Command] = StashBuffer[Command](Int.MaxValue)
      Behaviors.withTimers[Command] {
        implicit timer =>
          log.info(s"liveManager start----")
          work(mutable.Map[String, Long](),mutable.Map[Long, liveInfo]())
      }
    }
  }

  def work(roomLiveMap: mutable.Map[String, Long], roomInfoMap: mutable.Map[Long, liveInfo])
    (implicit timer: TimerScheduler[Command],
    stashBuffer: StashBuffer[Command]): Behavior[Command] = {
    log.info(s"liveManager turn to work state--")
    Behaviors.receive[Command] { (_, msg) =>
      msg match {
        case m@updateRoom(roomId: Long, liveId: String, startTime: Long) =>
          log.info(s"got msg: $m")
          roomInfoMap.put(roomId, liveInfo(roomId, liveId, 0, "正常", Constants.getMpdPath(roomId)))
          pullActor ! PullActor.NewLive(liveId, roomId)
//          killFFActor ! KillFFActor.NewLive(liveId, roomId)
          encodeManager ! EncodeManager.UpdateEncode(roomId, startTime)
          roomLiveMap.filter(_._2==roomId).foreach{e =>
            roomLiveMap.remove(e._1)
          }
          roomLiveMap.put(liveId, roomId)
          timer.startPeriodicTimer(s"liveStatusKey-$liveId", CheckStream(liveId), 2.seconds)
          Behaviors.same

        case KillFF =>
          roomLiveMap.foreach(e =>
            if (!PullActor.checkStream(e._1)){
              liveManager ! LiveManager.liveStop(e._1)
            }
          )
          Behaviors.same

        case RoomWithPort(roomId, port) =>
          roomInfoMap.get(roomId).foreach( r => roomInfoMap.update(r.roomId, liveInfo(r.roomId, r.liveId, port, r.status, r.Url)))
          Behaviors.same

        case liveStop(liveId) =>
          roomLiveMap.get(liveId).foreach{ roomId =>
            encodeManager ! EncodeManager.removeEncode(roomId)
            pullActor ! PullActor.RoomClose(roomId)
            roomInfoMap.remove(roomId)
          }
          roomLiveMap.remove(liveId)
          timer.cancel(s"liveStatusKey-$liveId")
          Behaviors.same

        case CheckStream(liveId) =>
          if (roomLiveMap.get(liveId).nonEmpty) {
            if (PullActor.checkStream(liveId))
              roomInfoMap.update(roomLiveMap(liveId),roomInfoMap(roomLiveMap(liveId)).copy(status = "正常"))
            else
              roomInfoMap.update(roomLiveMap(liveId),roomInfoMap(roomLiveMap(liveId)).copy(status = "断流"))
          }
          else {
            log.debug("liveId not exist")
            timer.cancel(s"liveStatusKey-$liveId")
          }
          Behaviors.same

        case CheckLive(liveId,reply) =>
          if (roomLiveMap.get(liveId).nonEmpty){
            if (PullActor.checkStream(liveId))
              reply ! CheckStreamRsp()
            else
              reply ! StreamError
          }
          else {
            log.warn(s"$liveId is not existed in roomLiveMap.")
            reply ! RoomError
          }
          Behaviors.same

        case GetAllLiveInfo(reply) =>
          val info = roomInfoMap.values.toList
          reply ! GetTransLiveInfoRsp(info = info)
          Behaviors.same
      }
    }
  }

  def removeTestFile():Unit = {
    val f = new File(s"${fileLocation}test/")
    if(f.exists()) {
      f.listFiles().foreach{
        e =>
          e.delete()
      }
    }
  }

  val testThread = new Thread(()=>
  {
    val ffmpeg = Loader.load(classOf[org.bytedeco.ffmpeg.ffmpeg])
    val pb = new ProcessBuilder(ffmpeg, "-re", "-stream_loop", "-1", "-i", indexPath+"test.mp4", "-c:v", "libx264", "-s", "720x576",
      "-c:a", "copy", "-f", "dash", "-window_size", "20", "-extra_window_size", "20", "-hls_playlist", "1", fileLocation+"test/index.mpd")
    pb.inheritIO().start()
  })

}
