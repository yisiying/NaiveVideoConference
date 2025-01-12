package org.seekloud.theia.roomManager.core

import java.util.concurrent.atomic.AtomicInteger

import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.{ActorContext, Behaviors, StashBuffer, TimerScheduler}
import akka.actor.typed.{ActorRef, Behavior}
import org.seekloud.theia.protocol.ptcl.CommonInfo.{LiveInfo, RoomInfo}
import org.seekloud.theia.protocol.ptcl.client2Manager.http.CommonProtocol._
import org.seekloud.theia.protocol.ptcl.client2Manager.websocket.AuthProtocol
import org.seekloud.theia.protocol.ptcl.processer2Manager.ProcessorProtocol.SeekRecord
import org.seekloud.theia.roomManager.Boot.{executor, scheduler, timeout}
import org.seekloud.theia.roomManager.common.AppSettings._
import org.seekloud.theia.roomManager.common.Common
import org.seekloud.theia.roomManager.models.dao.{RecordCommentDAO, RecordDao, UserInfoDao}
import org.seekloud.theia.roomManager.core.RoomActor._
import org.seekloud.theia.roomManager.protocol.ActorProtocol
import org.seekloud.theia.roomManager.protocol.CommonInfoProtocol.WholeRoomInfo
import org.seekloud.theia.roomManager.utils.{DistributorClient, ProcessorClient}
import org.slf4j.LoggerFactory
import org.seekloud.theia.roomManager.common.Common.{Like, Role}

import scala.collection.mutable
import scala.concurrent.duration.{FiniteDuration, _}
import scala.concurrent.Future
import scala.util.{Failure, Success}

/**
  * created by benyafang on 2019.7.16 am 10:32
  *
  * */
object RoomManager {
  private val log = LoggerFactory.getLogger(this.getClass)

  trait Command

  case class GetRoomList(replyTo: ActorRef[RoomListRsp]) extends Command

  case class SearchRoom(userId:  Option[Long], roomId: Long,  replyTo:ActorRef[SearchRoomRsp]) extends Command

  case class UserInfoChange(userId:Long,temporary:Boolean) extends Command

  case class ExistRoom(roomId:Long,replyTo:ActorRef[Boolean]) extends Command

  case class DelaySeekRecord(wholeRoomInfo:WholeRoomInfo, totalView:Int, roomId:Long, startTime:Long, liveId: String, invitationList: mutable.HashMap[Int, List[Long]]) extends Command
  case class OnSeekRecord(wholeRoomInfo:WholeRoomInfo, totalView:Int, roomId:Long, startTime:Long, liveId: String, invitationList: mutable.HashMap[Int, List[Long]]) extends Command

  case class AddAccess(roomId: Long, startTime: Long, invitationList: mutable.HashMap[Int, List[Long]]) extends Command

  case class GenLiveIdAndLiveCode(replyTo: ActorRef[LiveInfo]) extends Command

  case class GetRtmpLiveInfo(roomId:Long, replyTo:ActorRef[GetLiveInfoRsp4RM]) extends Command with RoomActor.Command

  private final case object DelaySeekRecordKey

  private final case object DelayAddAccessKey

  def create():Behavior[Command] = {
    Behaviors.setup[Command]{ctx =>
      implicit val stashBuffer = StashBuffer[Command](Int.MaxValue)
      log.info(s"${ctx.self.path} setup")
      Behaviors.withTimers[Command]{implicit timer =>
//        idle(mutable.HashMap.empty[Long,RoomInfo])
        var roomInfo = RoomInfo(Common.TestConfig.TEST_ROOM_ID,"test_room","测试房间",Common.TestConfig.TEST_USER_ID,
          "byf1",UserInfoDao.getHeadImg(""),
          UserInfoDao.getCoverImg(""),0,0,
          Some("test")
        )
        /*ProcessorClient.getmpd(Common.TestConfig.TEST_ROOM_ID).map{
          case Right(v) =>
            log.debug(s"${ctx.self.path} ${v.rtmp}")
            roomInfo = roomInfo.copy(rtmp = Some(v.rtmp))
          case Left(error) =>
            log.debug(s"${ctx.self.path} processor 获取失败：${error}")
        }*/
        log.debug(s"${ctx.self.path} ---===== ${roomInfo.rtmp}")

        getRoomActor(Common.TestConfig.TEST_ROOM_ID,ctx) ! TestRoom(roomInfo)
        val seq = new AtomicInteger(100001)
        idle(seq)
      }
    }
  }

  private def idle(
                    seq: AtomicInteger
//                    roomInUse:mutable.HashMap[Long,RoomInfo]
                  ) //roomId -> (roomInfo, liveInfo)
                  (implicit stashBuffer: StashBuffer[Command],timer:TimerScheduler[Command]):Behavior[Command] = {

    Behaviors.receive[Command]{(ctx,msg) =>
      msg match {
        case GenLiveIdAndLiveCode(replyTo) =>
          val liveId = s"user-${seq.getAndIncrement()}"
          val liveCode = "123456"
          log.info(s"gen liveId: $liveId success")
          replyTo ! LiveInfo(liveId, liveCode)
          Behaviors.same

        case GetRoomList(replyTo) =>
          val roomInfoListFuture = ctx.children.map(_.unsafeUpcast[RoomActor.Command]).map{r =>
            val roomInfoFuture:Future[RoomInfo] = r ? (GetRoomInfo(_))
            roomInfoFuture
          }.toList
          Future.sequence(roomInfoListFuture).map{seq =>
            replyTo ! RoomListRsp(Some(seq.filter(r => r.rtmp.nonEmpty || r.roomName == "test_room")))
          }
          Behaviors.same

        case r@ActorProtocol.AddUserActor4Test(userId,roomId,userActor) =>
          getRoomActorOpt(roomId,ctx) match {
            case Some(actor) =>actor ! r
            case None =>
          }
          Behaviors.same

        case r@ActorProtocol.WebSocketMsgWithActor(userId,roomId,req) =>
          getRoomActorOpt(roomId,ctx) match{
            case Some(actor) => actor ! r
            case None => log.debug(s"${ctx.self.path}请求错误，该房间还不存在，房间id=$roomId，用户id=$userId")
          }
          Behaviors.same

        case r@ActorProtocol.StartLiveAgain(roomId) =>
          getRoomActorOpt(roomId,ctx) match{
            case Some(actor) => actor ! r
            case None => log.debug(s"${ctx.self.path}重新直播请求错误，该房间已经关闭，房间id=$roomId")
          }
          Behaviors.same


        case r@ActorProtocol.HostCloseRoom(roomId)=>
          //如果断开websocket的用户的id能够和已经开的房间里面的信息匹配上，就说明是主播
          getRoomActorOpt(roomId, ctx) match{
            case Some(roomActor) => roomActor ! r
            case None =>log.debug(s"${ctx.self.path}关闭房间失败，房间不存在，id=$roomId")
          }
          Behaviors.same

        case r@ActorProtocol.UpdateSubscriber(join,roomId,userId,temporary,userActor) =>
          getRoomActorOpt(roomId,ctx)match{
            case Some(actor) =>actor ! r
            case None =>log.debug(s"${ctx.self.path}更新用户信息失败，房间不存在，有可能该用户是主播等待房间开启，房间id=$roomId,用户id=$userId")
          }
          Behaviors.same

        case r@ActorProtocol.UpdateInvitationList(roomId, userId) =>
          getRoomActorOpt(roomId,ctx) match {
            case Some(actor) => actor ! r
            case None => log.debug(s"${ctx.self.path}房间未建立，roomId：$roomId")
          }
          Behaviors.same

        case r@ActorProtocol.StartRoom4Anchor(userId,roomId,actor) =>
          getRoomActor(roomId,ctx) ! r
          Behaviors.same


        case r@GetRtmpLiveInfo(roomId, replyTo)=>
          getRoomActorOpt(roomId,ctx) match{
            case Some(actor) =>actor ! r
            case None =>
              log.debug(s"${ctx.self.path}房间未建立")
              replyTo ! GetLiveInfoRsp4RM(None,100041,s"获取live info 请求失败:房间不存在")
          }
          Behaviors.same

        case r@ActorProtocol.BanOnAnchor(roomId) =>
          getRoomActorOpt(roomId,ctx) match{
            case Some(actor) =>actor ! r
            case None =>
              log.debug(s"${ctx.self.path}房间未建立")
          }
          Behaviors.same

        case SearchRoom(userId, roomId, replyTo) =>
          if(roomId == Common.TestConfig.TEST_ROOM_ID){
            log.debug(s"${ctx.self.path} get test room mpd,roomId=${roomId}")
            getRoomActorOpt(roomId,ctx) match{
              case Some(actor) =>
                val roomInfoFuture:Future[RoomInfo] = actor ? (GetRoomInfo(_))
                roomInfoFuture.map{r =>replyTo ! SearchRoomRsp(Some(r))}
              case None =>
                log.debug(s"${ctx.self.path} test room dead")
                replyTo ! SearchRoomError4RoomId
            }
          } else{
            getRoomActorOpt(roomId,ctx) match{
              case Some(actor) =>
                val roomInfoFuture:Future[RoomInfo] = actor ? (GetRoomInfo(_))
                roomInfoFuture.map{r =>
                  r.rtmp match {
                    case Some(v) =>
                      log.debug(s"${ctx.self.path} search room,roomId=${roomId},rtmp=${r.rtmp}")
                      replyTo ! SearchRoomRsp(Some(r))//正常返回
                    case None =>
                      log.debug(s"${ctx.self.path} search room failed,roomId=${roomId},rtmp=None")
                      replyTo ! SearchRoomError(msg = s"${ctx.self.path} room rtmp is None")
                      /*ProcessorClient.getmpd(roomId).map{
                        case Right(rsp)=>
                          if(rsp.errCode == 0){
                            actor ! UpdateRTMP(rsp.rtmp)
                            val roomInfoFuture:Future[RoomInfo] = actor ? (GetRoomInfo(_))
                            roomInfoFuture.map{w =>
                              log.debug(s"${ctx.self.path} research room,roomId=${roomId},rtmp=${r.rtmp}")
                              replyTo ! SearchRoomRsp(Some(w))}//正常返回
                          }else if(rsp.errCode == 100024){
                            //replyTo ! SearchRoomRsp(Some(r))
                            replyTo ! SearchRoomRsp(Some(r), 100009, "stop push stream")//主播停播，房间还在
                          }
                          else{
                          log.info(s"getmpd code:${rsp.errCode}, msg${rsp.msg}")
                            replyTo ! SearchRoomError(errCode = rsp.errCode, msg = rsp.msg)//
                          }

                        case Left(error)=>
                          replyTo ! SearchRoomError4ProcessorDead//请求processor失败
                      }*/

                  }
                }
              case None =>
                log.debug(s"${ctx.self.path} test room dead")
                replyTo ! SearchRoomError4RoomId//主播关闭房间
            }
          }
          Behaviors.same

        case ExistRoom(roomId,replyTo) =>
          getRoomActorOpt(roomId,ctx) match {
            case Some(actor) =>
              replyTo ! true
            case None =>
              replyTo ! false
          }
          Behaviors.same

        case UserInfoChange(userId,temporary) =>
          UserInfoDao.searchById(userId).map{
              case Some(v) =>
                getRoomActorOpt(v.roomid,ctx)match{
                  case Some(roomActor) =>
                    roomActor ! ActorProtocol.UpdateSubscriber(Common.Subscriber.change,v.roomid,userId,temporary,None)
                  case None =>
                    log.debug(s"${ctx.self.path}更新房间 ${v.roomid}的用户信息userId:$userId")
                }
              case None =>
                log.debug(s"${ctx.self.path}更新用户信息失败，用户信息不存在，userId:$userId")
          }
          Behaviors.same

        //延时请求获取录像（计时器）
        case DelaySeekRecord(wholeRoomInfo, totalView, roomId, startTime, liveId, invitationList) =>
          log.info("---- wait seconds to seek record ----")
          timer.startSingleTimer(DelaySeekRecordKey + roomId.toString + startTime, OnSeekRecord(wholeRoomInfo, totalView, roomId, startTime, liveId, invitationList), 5.seconds)
          Behaviors.same

        //延时请求获取录像
        case OnSeekRecord(wholeRoomInfo, totalView, roomId, startTime, liveId, invitationList) =>
          timer.cancel(DelaySeekRecordKey + roomId.toString + startTime)
          DistributorClient.seekRecord(roomId,startTime).onComplete{
            case Success(v) =>
              v match{
                case Right(rsp) =>
                  log.debug(s"${ctx.self.path}获取录像id${roomId}时长为duration=${rsp.duration}")
                  RecordDao.addRecord(wholeRoomInfo.roomInfo.roomId,
                    wholeRoomInfo.roomInfo.roomName,wholeRoomInfo.roomInfo.roomDes,startTime,
                    UserInfoDao.getVideoImg(wholeRoomInfo.roomInfo.coverImgUrl),0,wholeRoomInfo.roomInfo.like,rsp.duration)
                //todo 添加RecordCommentDAO.addCommentAccess()，可以用ctx.self ! addAccess()
//                  timer.startSingleTimer(DelayAddAccessKey + roomId.toString + startTime, AddAccess(roomId, startTime, invitationList),3.seconds)
                case Left(err) =>
                  log.debug(s"${ctx.self.path} 查询录像文件信息失败,error:$err")
              }
            case Failure(error) =>
              log.debug(s"${ctx.self.path} 查询录像文件失败,error:$error")
          }
          val host = invitationList(Role.host).head
          RecordCommentDAO.addCommentAccess(wholeRoomInfo.roomInfo.roomId, startTime, host, host)
          if(invitationList.contains(Role.audience)){
            invitationList(Role.audience).foreach{u =>
              RecordCommentDAO.addCommentAccess(wholeRoomInfo.roomInfo.roomId, startTime, host, u)
            }
          }

          Behaviors.same

//        case AddAccess(roomId, startTime, invitationList) =>
//          timer.cancel(DelayAddAccessKey + roomId.toString + startTime)
//          RecordDao.searchRecord(roomId, startTime).map{
//            case Some(recordInfo) =>
//              val host = invitationList(Role.host).head
//              RecordCommentDAO.addCommentAccess(recordInfo.recordId, host, host)
//              invitationList(Role.audience).foreach{u =>
//                RecordCommentDAO.addCommentAccess(recordInfo.recordId, host, u)
//              }
//              log.info(s"录像${recordInfo.recordId}添加权限成功")
//            case None =>
//              log.error(s"未录像信息，无法添加权限")
//          }
//
//          Behavior.same


        case ChildDead(name,childRef) =>
          log.debug(s"${ctx.self.path} the child = ${ctx.children}")
          Behaviors.same

        case x =>
          log.debug(s"${ctx.self.path} recv an unknown msg")
          Behaviors.same
      }
    }
  }


  private def getRoomActor(roomId:Long, ctx: ActorContext[Command]) = {
    val childrenName = s"roomActor-${roomId}"
    ctx.child(childrenName).getOrElse {
      val actor = ctx.spawn(RoomActor.create(roomId), childrenName)
      ctx.watchWith(actor,ChildDead(childrenName,actor))
      actor
    }.unsafeUpcast[RoomActor.Command]
  }

  private def getRoomActorOpt(roomId:Long, ctx: ActorContext[Command]) = {
    val childrenName = s"roomActor-${roomId}"
//    log.debug(s"${ctx.self.path} the child = ${ctx.children},get the roomActor opt = ${ctx.child(childrenName).map(_.unsafeUpcast[RoomActor.Command])}")
    ctx.child(childrenName).map(_.unsafeUpcast[RoomActor.Command])

  }


}
