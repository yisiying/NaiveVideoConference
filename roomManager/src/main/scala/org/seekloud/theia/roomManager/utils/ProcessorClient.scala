package org.seekloud.theia.roomManager.utils

import akka.event.jul.Logger
import SecureUtil.genPostEnvelope
import org.slf4j.LoggerFactory
import org.seekloud.theia.roomManager.Boot.{executor, scheduler, system, timeout}
import org.seekloud.theia.protocol.ptcl.processer2Manager.ProcessorProtocol
import org.seekloud.theia.protocol.ptcl.processer2Manager.ProcessorProtocol._
import org.seekloud.theia.roomManager.common.AppSettings
import org.seekloud.theia.roomManager.common.AppSettings.distributorDomain
import org.seekloud.theia.roomManager.http.ServiceUtils.CommonRsp

import scala.concurrent.Future
/**
  * created by byf on 2019.7.17 13:09
  * */
object ProcessorClient extends HttpUtil{

  import io.circe.generic.auto._
  import io.circe.syntax._
  import io.circe.parser.decode

  private val log = LoggerFactory.getLogger(this.getClass)

  val processorBaseUrl = s"http://${AppSettings.processorIp}:${AppSettings.processorPort}/theia/processor"
  val distributorBaseUrl = s"https://$distributorDomain/theia/distributor"

  def newConnect(roomId:Long, liveId4host: String, liveId4client: String, liveId4push: String, liveCode4push: String, layout: Int):Future[Either[String,newConnectRsp]] = {
    val url = processorBaseUrl + "/newConnect"
    val jsonString = newConnectInfo(roomId, liveId4host, liveId4client, liveId4push, liveCode4push, layout).asJson.noSpaces
    postJsonRequestSend("newConnect",url,List(),jsonString,timeOut = 60 * 1000,needLogRsp = false).map{
      case Right(v) =>
        decode[newConnectRsp](v) match{
          case Right(value) =>
            Right(value)
          case Left(e) =>
            log.error(s"newConnect decode error : $e")
            Left("Error")
        }
      case Left(error) =>
        log.error(s"newConnect postJsonRequestSend error : $error")
        Left("Error")
    }

  }

  def updateRoomInfo(roomId:Long,layout:Int):Future[Either[String,UpdateRsp]] = {
    val url = processorBaseUrl + "/updateRoomInfo"
    val jsonString = ProcessorProtocol.UpdateRoomInfo(roomId, layout).asJson.noSpaces
    postJsonRequestSend("updateRoomInfo",url,List(),jsonString,timeOut = 60 * 1000,needLogRsp = false).map{
      case Right(v) =>
        decode[UpdateRsp](v) match{
          case Right(data) =>
            Right(data)
          case Left(e) =>
            log.error(s"updateRoomInfo decode error : $e")
            Left(s"updateRoomInfo decode error : $e")
        }
      case Left(error) =>
        log.error(s"updateRoomInfo postJsonRequestSend error : $error")
        Left(s"updateRoomInfo postJsonRequestSend error : $error")
    }
  }

  def getmpd(roomId:Long):Future[Either[String,MpdRsp]] = {
    val url = processorBaseUrl + "/getMpd"
    val jsonString = GetMpd(roomId).asJson.noSpaces
    postJsonRequestSend("get mpd",url,List(),jsonString,timeOut = 60 * 1000, needLogRsp = false).map{
      case Right(v) =>
        decode[MpdRsp](v) match{
          case Right(data) =>
            Right(data)
          case Left(e) =>
            log.error(s"getmpd decode error : $e")
            Left("Error")
        }
      case Left(error) =>
        log.error(s"getmpd postJsonRequestSend error : $error")
        Left("Error")
    }
  }

  def closeRoom(roomId:Long):Future[Either[String,CloseRsp]] = {
    val url = processorBaseUrl + "/closeRoom"
    val jsonString = CloseRoom(roomId).asJson.noSpaces
    postJsonRequestSend("closeRoom",url,List(),jsonString,timeOut = 60 * 1000,needLogRsp = false).map{
      case Right(v) =>
        decode[CloseRsp](v) match{
          case Right(value) =>
            Right(value)
          case Left(e) =>
            log.error(s"closeRoom decode error : $e")
            Left("Error")
        }
      case Left(error) =>
        log.error(s"closeRoom postJsonRequestSend error : $error")
        Left("Error")
    }

  }


}
