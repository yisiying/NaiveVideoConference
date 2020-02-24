package org.seekloud.theia.roomManager.core

import akka.actor
import akka.actor.typed.scaladsl.{ActorContext, Behaviors, StashBuffer, TimerScheduler}
import akka.actor.typed.{ActorRef, Behavior}
import org.seekloud.byteobject.MiddleBufferInJvm
import org.seekloud.theia.protocol.ptcl.CommonInfo._
import org.seekloud.theia.protocol.ptcl.client2Manager.http.CommonProtocol.{GetLiveInfoRsp, GetLiveInfoRsp4RM}
import org.seekloud.theia.protocol.ptcl.client2Manager.websocket.AuthProtocol
import org.seekloud.theia.protocol.ptcl.client2Manager.websocket.AuthProtocol.{HostCloseRoom, _}
import org.seekloud.theia.roomManager.Boot.{executor, roomManager}
import org.seekloud.theia.roomManager.common.AppSettings.{distributorIp, distributorPort}
import org.seekloud.theia.roomManager.common.Common
import org.seekloud.theia.roomManager.common.Common.{Like, Role, Part}
import org.seekloud.theia.roomManager.core.RoomManager.GetRtmpLiveInfo
import org.seekloud.theia.roomManager.models.dao.{RecordDao, UserInfoDao}
import org.seekloud.theia.roomManager.protocol.ActorProtocol
import org.seekloud.theia.roomManager.protocol.ActorProtocol.BanOnAnchor
import org.seekloud.theia.roomManager.protocol.CommonInfoProtocol.WholeRoomInfo
import org.seekloud.theia.roomManager.utils.{DistributorClient, ProcessorClient}
import org.slf4j.LoggerFactory

import scala.collection.mutable
import scala.concurrent.duration.{FiniteDuration, _}
import scala.util.{Failure, Success}
import org.seekloud.theia.roomManager.core.UserActor

object RoomActor {

  import org.seekloud.byteobject.ByteObject._

  import scala.language.implicitConversions

  private val log = LoggerFactory.getLogger(this.getClass)

  trait Command

  final case class ChildDead[U](name: String, childRef: ActorRef[U]) extends Command with RoomManager.Command

  private final case class SwitchBehavior(
                                           name: String,
                                           behavior: Behavior[Command],
                                           durationOpt: Option[FiniteDuration] = None,
                                           timeOut: TimeOut = TimeOut("busy time error")
                                         ) extends Command

  private case class TimeOut(msg: String) extends Command

  private final case object BehaviorChangeKey

  private[this] def switchBehavior(ctx: ActorContext[Command],
                                   behaviorName: String, behavior: Behavior[Command], durationOpt: Option[FiniteDuration] = None, timeOut: TimeOut = TimeOut("busy time error"))
                                  (implicit stashBuffer: StashBuffer[Command],
                                   timer: TimerScheduler[Command]) = {
    timer.cancel(BehaviorChangeKey)
    durationOpt.foreach(timer.startSingleTimer(BehaviorChangeKey, timeOut, _))
    stashBuffer.unstashAll(ctx, behavior)
  }

  final case class GetRoomInfo(replyTo: ActorRef[RoomInfo]) extends Command //考虑后续房间的建立不依赖ws
  final case class UpdateRTMP(rtmp: String) extends Command

  final case class CheckAccess(replyTo: ActorRef[Boolean], userId: Long) extends Command

  private final val InitTime = Some(5.minutes)

  def create(roomId: Long): Behavior[Command] = {
    Behaviors.setup[Command] { ctx =>
      implicit val stashBuffer = StashBuffer[Command](Int.MaxValue)
      log.debug(s"${ctx.self.path} setup")
      Behaviors.withTimers[Command] { implicit timer =>
        implicit val sendBuffer: MiddleBufferInJvm = new MiddleBufferInJvm(81920) //8192
      val subscribers = mutable.HashMap.empty[(Long, Boolean), ActorRef[UserActor.Command]]
        init(roomId, subscribers)
      }
    }
  }

  private def init(
                    roomId: Long,
                    subscribers: mutable.HashMap[(Long, Boolean), ActorRef[UserActor.Command]],
                    roomInfoOpt: Option[RoomInfo] = None
                  )
                  (
                    implicit stashBuffer: StashBuffer[Command],
                    timer: TimerScheduler[Command],
                    sendBuffer: MiddleBufferInJvm
                  ): Behavior[Command] = {
    Behaviors.receive[Command] { (ctx, msg) =>
      msg match {
        case ActorProtocol.StartRoom4Anchor(userId, `roomId`, actor) =>
          log.debug(s"${ctx.self.path} 用户id=$userId 开启了的新的直播间id=$roomId")
          subscribers.put((userId, false), actor)
          for {
            userTableOpt <- UserInfoDao.searchById(userId)
          } yield {
            if (userTableOpt.nonEmpty) {
              val roomInfo = RoomInfo(roomId, s"${userTableOpt.get.userName}的会议室", "", userTableOpt.get.uid, userTableOpt.get.userName,
                UserInfoDao.getHeadImg(userTableOpt.get.headImg),
                UserInfoDao.getHeadImg(userTableOpt.get.coverImg), 0, 0,
                Some(s"room-$roomId")
              )
              //              ProcessorClient.startRoom(roomId, s"user-${userTableOpt.get.uid}", roomInfo.rtmp.get, 0).map {
              //                case Right(r) =>
              //                  log.info(s"processor start mix stream")
                  //                  DistributorClient.startPull(roomId, "main").map {
                  //                    case Right(r) =>
                  //                      log.info(s"distributor startPull succeed, get live address: ${r.liveAdd}")
                  dispatchTo(subscribers)(List((userId, false)), StartLiveRsp(Some(LiveInfo(roomInfo.rtmp.get))))
                  val startTime = System.currentTimeMillis()
                  ctx.self ! SwitchBehavior("idle", idle(WholeRoomInfo(roomInfo),
                    mutable.HashMap(Role.host -> mutable.HashMap(userId -> LiveInfo(s"user-${userTableOpt.get.uid}"))),
                    subscribers,
                    startTime,
                    mutable.HashMap(Role.host -> List((userTableOpt.get.uid, userTableOpt.get.userName)))
                  )
                  )

                //                    case Left(e) =>
                //                      log.error(s"distributor startPull error: $e")
                //                      dispatchTo(subscribers)(List((userId, false)), StartLiveRefused4LiveInfoError)
                //                      ctx.self ! SwitchBehavior("init", init(roomId, subscribers))
                //                  }
              //                case Left(e) =>
              //                  log.error(s"processor start room error:$e")
              //                  ctx.self ! SwitchBehavior("init", init(roomId, subscribers))
              //              }
            } else {
              log.debug(s"${ctx.self.path} 开始直播被拒绝，数据库中没有该用户的数据，userId=$userId")
              dispatchTo(subscribers)(List((userId, false)), StartLiveRefused)
              ctx.self ! SwitchBehavior("init", init(roomId, subscribers))
            }
          }
          switchBehavior(ctx, "busy", busy(), InitTime, TimeOut("busy"))

        case GetRoomInfo(replyTo) =>
          if (roomInfoOpt.nonEmpty) {
            replyTo ! roomInfoOpt.get
          } else {
            log.debug("房间信息未更新")
            replyTo ! RoomInfo(-1, "", "", -1l, "", "", "", -1, -1)
          }
          Behaviors.same

        case ActorProtocol.AddUserActor4Test(userId, roomId, userActor) =>
          subscribers.put((userId, false), userActor)
          Behaviors.same

        case x =>
          log.debug(s"${ctx.self.path} recv an unknown msg:$x in init state...")
          Behaviors.same
      }
    }
  }

  private def idle(
                    wholeRoomInfo: WholeRoomInfo, //可以考虑是否将主路的liveinfo加在这里，单独存一份连线者的liveinfo列表
                    liveInfoMap: mutable.HashMap[Int, mutable.HashMap[Long, LiveInfo]],
                    subscribe: mutable.HashMap[(Long, Boolean), ActorRef[UserActor.Command]], //需要区分订阅的用户的身份，注册用户还是临时用户(uid,是否是临时用户true:是)
                    startTime: Long,
                    invitationList: mutable.HashMap[Int, List[(Long, String)]],
                    isJoinOpen: Boolean = true,
                  )
                  (implicit stashBuffer: StashBuffer[Command],
                   timer: TimerScheduler[Command],
                   sendBuffer: MiddleBufferInJvm
                  ): Behavior[Command] = {
    Behaviors.receive[Command] { (ctx, msg) =>
      msg match {
        case ActorProtocol.AddUserActor4Test(userId, roomId, userActor) =>
          subscribe.put((userId, false), userActor)
          Behaviors.same

        case GetRoomInfo(replyTo) =>
          replyTo ! wholeRoomInfo.roomInfo
          Behaviors.same

        case CheckAccess(replyTo, userId) =>
          replyTo ! invitationList(Role.audience).map(_._1).contains(userId)
          Behaviors.same

        case UpdateRTMP(rtmp) =>
          val newRoomInfo = wholeRoomInfo.copy(roomInfo = wholeRoomInfo.roomInfo.copy(rtmp = Some(rtmp)))
          log.debug(s"${ctx.self.path} 更新liveId=$rtmp,更新后的liveId=${newRoomInfo.roomInfo.rtmp} ---idle")
          idle(newRoomInfo, liveInfoMap, subscribe, startTime, invitationList, isJoinOpen)

        case ActorProtocol.UpdateInvitationList(roomId, userId, inOrOut) =>
          //          if (inOrOut == Part.in) {
          //            val l = invitationList(Role.audience)
          //            val newList = userId :: l
          //            invitationList.update(Role.audience, newList)
          //          } else if (inOrOut == Part.out) {
          //            val l = invitationList(Role.audience)
          //            val newList = l.filterNot(_ == userId)
          //            invitationList.update(Role.audience, newList)
          //          }
          idle(wholeRoomInfo, liveInfoMap, subscribe, startTime, invitationList, isJoinOpen)

        case ActorProtocol.WebSocketMsgWithActor(userId, roomId, wsMsg) =>
          handleWebSocketMsg(wholeRoomInfo, subscribe, liveInfoMap, startTime, isJoinOpen, dispatch(subscribe), dispatchTo(subscribe), invitationList)(ctx, userId, roomId, wsMsg)

        case GetRtmpLiveInfo(_, replyTo) =>
          log.debug(s"room${wholeRoomInfo.roomInfo.roomId}获取liveId成功   ---idle")
          liveInfoMap.get(Role.host) match {
            case Some(value) =>
              replyTo ! GetLiveInfoRsp4RM(Some(value.values.head))
            case None =>
              log.debug(s"${ctx.self.path} no host live info,roomId=${wholeRoomInfo.roomInfo.roomId}")
              replyTo ! GetLiveInfoRsp4RM(None)

          }
          Behaviors.same

        case ActorProtocol.UpdateSubscriber(join, roomId, userId, temporary, userActorOpt) =>
          //虽然房间存在，但其实主播已经关闭房间，这时的startTime=-1
          //向所有人发送主播已经关闭房间的消息
          log.info(s"-----roomActor get UpdateSubscriber id: $roomId   ---idle")
          if (startTime == -1) {
            dispatchTo(subscribe)(List((userId, temporary)), NoAuthor)
          }
          else {
            if (join == Common.Subscriber.join) {
              // todo observe event
              log.debug(s"${ctx.self.path}新用户加入房间roomId=$roomId,userId=$userId")
              subscribe.put((userId, temporary), userActorOpt.get)
              if (liveInfoMap.contains(Role.audience)) {
                val value = liveInfoMap(Role.audience)
                value.put(userId, LiveInfo(s"user-$userId"))
                liveInfoMap.put(Role.audience, value)
              } else {
                liveInfoMap.put(Role.audience, mutable.HashMap(userId -> LiveInfo(s"user-$userId")))
              }
            } else if (join == Common.Subscriber.left) {
              // todo observe event
              log.debug(s"${ctx.self.path}用户离开房间roomId=$roomId,userId=$userId")
              subscribe.remove((userId, temporary))
              if (liveInfoMap.contains(Role.audience)) {
                if (liveInfoMap(Role.audience).contains(userId)) {
                  wholeRoomInfo.roomInfo.rtmp match {
                    case Some(v) =>
                      if (v != liveInfoMap(Role.host)(wholeRoomInfo.roomInfo.userId).liveId) {
                        liveInfoMap.remove(Role.audience)
                        //                        ProcessorClient.closeRoom(roomId)
                        //                        ctx.self ! UpdateRTMP(liveInfoMap(Role.host)(wholeRoomInfo.roomInfo.userId).liveId)
                        dispatch(subscribe)(AuthProtocol.AudienceDisconnect(liveInfoMap(Role.host)(wholeRoomInfo.roomInfo.userId).liveId))
                        dispatch(subscribe)(RcvComment(-1l, "", s"the audience has shut the join in room $roomId"))
                      }
                    case None =>
                      log.debug("no host liveId when audience left room")
                  }
                }
              }
            }
          }
          //所有的注册用户
          val audienceList = subscribe.filterNot(_._1 == (wholeRoomInfo.roomInfo.userId, false)).keys.toList.filter(r => !r._2).map(_._1)
          //          val temporaryList = subscribe.filterNot(_._1 == (wholeRoomInfo.roomInfo.userId, false)).keys.toList.filter(r => r._2).map(_._1)
          UserInfoDao.getUserDes(audienceList).onComplete {
            case Success(rst) =>
              //              val temporaryUserDesList = temporaryList.map(r => UserDes(r, s"guest_$r", Common.DefaultImg.headImg))
              dispatch(subscribe)(UpdateAudienceInfo(rst))
            case Failure(_) =>

          }
          wholeRoomInfo.roomInfo.observerNum = subscribe.size - 1
          idle(wholeRoomInfo, liveInfoMap, subscribe, startTime, invitationList, isJoinOpen)

        case ActorProtocol.HostCloseRoom(roomId) =>
          log.debug(s"${ctx.self.path} host close the room   ---idle")
          wholeRoomInfo.roomInfo.rtmp match {
            case Some(v) =>
              if (v != liveInfoMap(Role.host)(wholeRoomInfo.roomInfo.userId).liveId) {
                ProcessorClient.closeRoom(wholeRoomInfo.roomInfo.roomId)
              }
            case None =>
          }
          //          if (wholeRoomInfo.roomInfo.rtmp.get != liveInfoMap(Role.host)(wholeRoomInfo.roomInfo.userId).liveId)
          //            ProcessorClient.closeRoom(wholeRoomInfo.roomInfo.roomId)
          if (startTime != -1l) {
            log.debug(s"${ctx.self.path} 主播向distributor发送finishPull请求")
            //            log.debug(s"!!!!!!!!!!!!!!$invitationList")
            DistributorClient.finishPull(liveInfoMap(Role.host)(wholeRoomInfo.roomInfo.userId).liveId)
            roomManager ! RoomManager.DelaySeekRecord(wholeRoomInfo, roomId, startTime, liveInfoMap(Role.host)(wholeRoomInfo.roomInfo.userId).liveId, invitationList)
          }
          dispatchTo(subscribe)(subscribe.filter(r => r._1 != (wholeRoomInfo.roomInfo.userId, false)).keys.toList, HostCloseRoom())
          Behaviors.stopped

        case ActorProtocol.StartLiveAgain(roomId) =>
          log.debug(s"${ctx.self.path} the room actor has been exist,the host restart the room")
          ProcessorClient.startRoom(roomId, s"user-${wholeRoomInfo.roomInfo.userId}", wholeRoomInfo.roomInfo.rtmp.get, 0).map {
            case Right(r) =>
              log.info(s"processor start mix stream")
              liveInfoMap.put(Role.host, mutable.HashMap(wholeRoomInfo.roomInfo.userId -> LiveInfo(s"user-${wholeRoomInfo.roomInfo.userId}")))
              val startTime = System.currentTimeMillis()
              val newWholeRoomInfo = wholeRoomInfo.copy(roomInfo = wholeRoomInfo.roomInfo.copy(observerNum = 0, like = 0, rtmp = Some(s"room-$roomId")))
              dispatchTo(subscribe)(List((wholeRoomInfo.roomInfo.userId, false)), StartLiveRsp(Some(LiveInfo(wholeRoomInfo.roomInfo.rtmp.get))))
              idle(newWholeRoomInfo, liveInfoMap, subscribe, startTime, invitationList, isJoinOpen)
            //              ctx.self ! SwitchBehavior("idle", idle(newWholeRoomInfo, liveInfoMap, subscribe, startTime, invitationList, isJoinOpen))
            //              switchBehavior(ctx, "busy", busy(), InitTime, TimeOut("busy"))
            case Left(e) =>
              log.error(s"processor start room error:$e")
            //                  ctx.self ! SwitchBehavior("init", init(roomId, subscribers))
          }
          Behaviors.same


        case BanOnAnchor(roomId) =>
          //ProcessorClient.closeRoom(wholeRoomInfo.roomInfo.roomId)
          dispatchTo(subscribe)(subscribe.filter(r => r._1 != (wholeRoomInfo.roomInfo.userId, false)).keys.toList, HostCloseRoom())
          dispatchTo(subscribe)(List((wholeRoomInfo.roomInfo.userId, false)), AuthProtocol.BanOnAnchor)
          Behaviors.stopped

        case x =>
          log.debug(s"${ctx.self.path} recv an unknown msg $x")
          Behaviors.same
      }
    }
  }

  private def busy()
                  (
                    implicit stashBuffer: StashBuffer[Command],
                    timer: TimerScheduler[Command],
                    sendBuffer: MiddleBufferInJvm
                  ): Behavior[Command] =
    Behaviors.receive[Command] { (ctx, msg) =>
      msg match {
        case SwitchBehavior(name, b, durationOpt, timeOut) =>
          switchBehavior(ctx, name, b, durationOpt, timeOut)

        case TimeOut(m) =>
          log.debug(s"${ctx.self.path} is time out when busy, msg=$m")
          Behaviors.stopped

        case x =>
          stashBuffer.stash(x)
          Behavior.same

      }
    }

  //websocket处理消息的函数
  /**
    * userActor --> roomManager --> roomActor --> userActor
    * roomActor
    * subscribers:map(userId,userActor)
    *
    *
    *
    **/
  private def handleWebSocketMsg(
                                  wholeRoomInfo: WholeRoomInfo,
                                  subscribers: mutable.HashMap[(Long, Boolean), ActorRef[UserActor.Command]], //包括主播在内的所有用户
                                  liveInfoMap: mutable.HashMap[Int, mutable.HashMap[Long, LiveInfo]], //"audience"/"anchor"->Map(userId->LiveInfo)
                                  startTime: Long,
                                  isJoinOpen: Boolean = true,
                                  dispatch: WsMsgRm => Unit,
                                  dispatchTo: (List[(Long, Boolean)], WsMsgRm) => Unit,
                                  invitationList: mutable.HashMap[Int, List[(Long, String)]],
                                )
                                (ctx: ActorContext[Command], userId: Long, roomId: Long, msg: WsMsgClient)
                                (
                                  implicit stashBuffer: StashBuffer[Command],
                                  timer: TimerScheduler[Command],
                                  sendBuffer: MiddleBufferInJvm
                                ): Behavior[Command] = {
    msg match {
      case ChangeLiveMode(isConnectOpen, aiMode, screenLayout) =>
        log.info(s"get ChangeLiveMode msg: $isConnectOpen, $aiMode, $screenLayout")
        val connect = isConnectOpen match {
          case Some(v) => v
          case None => isJoinOpen
        }
        val liveList = liveInfoMap.toList.sortBy(_._1).flatMap(r => r._2).map(_._2.liveId)
        if (aiMode.isEmpty && screenLayout.nonEmpty) {
          wholeRoomInfo.layout = screenLayout.get
        } else if (aiMode.nonEmpty && screenLayout.isEmpty) {
          //          wholeRoomInfo.aiMode = aiMode.get
        } else if (aiMode.nonEmpty && screenLayout.nonEmpty) {
          wholeRoomInfo.layout = screenLayout.get
          //          wholeRoomInfo.aiMode = aiMode.get
        }
        //        if (!(aiMode.isEmpty && screenLayout.isEmpty)) {
        //          changeMode(ctx, userId, dispatchTo)(roomId, liveList, wholeRoomInfo.layout, wholeRoomInfo.aiMode, 0l)
        //        } else {
        //          dispatchTo(List((wholeRoomInfo.roomInfo.userId, false)), ChangeModeRsp())
        //        }
        idle(wholeRoomInfo, liveInfoMap, subscribers, startTime, invitationList, connect)

      case AddPartner(userName) =>
        log.info(s"add user: $userName")
        val l = invitationList(Role.audience)
        UserInfoDao.searchByName(userName).map {
          case Some(i) =>
            val newList = (i.uid, userName) :: l
            invitationList.update(Role.audience, newList)
            dispatchTo(List((wholeRoomInfo.roomInfo.userId, false)), UpdatePartnerRsp(newList))
          case None =>
            dispatchTo(List((wholeRoomInfo.roomInfo.userId, false)), UpdatePartnerRsp(l, 101015, "no user"))
        }
        idle(wholeRoomInfo, liveInfoMap, subscribers, startTime, invitationList, isJoinOpen)

      case DeletePartner(userName) =>
        log.info(s"delete user: $userName")
        val l = invitationList(Role.audience)
        val newList = l.filterNot(_._2 == userName)
        invitationList.update(Role.audience, newList)
        dispatchTo(List((wholeRoomInfo.roomInfo.userId, false)), UpdatePartnerRsp(newList))
        idle(wholeRoomInfo, liveInfoMap, subscribers, startTime, invitationList, isJoinOpen)



      case JoinAccept(`roomId`, userId4Audience, clientType, accept) =>
        log.debug(s"${ctx.self.path} 接受连线者请求，roomId=$roomId   ---ws")
        if (accept) {
          for {
            userInfoOpt <- UserInfoDao.searchById(userId4Audience)
          } yield {
            if (userInfoOpt.nonEmpty) {
              liveInfoMap.get(Role.host) match {
                case Some(value) =>
                  val liveIdHost = value.get(wholeRoomInfo.roomInfo.userId)
                  if (liveIdHost.nonEmpty) {
                    liveInfoMap.get(Role.audience) match {
                      case Some(value4Audience) =>
                        value4Audience.put(userId4Audience, LiveInfo(s"user-${userId4Audience}"))
                        liveInfoMap.put(Role.audience, value4Audience)
                      case None =>
                        liveInfoMap.put(Role.audience, mutable.HashMap(userId4Audience -> LiveInfo(s"user-${userId4Audience}")))
                    }
                    liveIdHost.foreach { HostLiveInfo =>
                      //                      DistributorClient.startPull(roomId, liveInfo4Mix.liveId)
                      ProcessorClient.newConnect(roomId, s"user-${userId4Audience}", wholeRoomInfo.roomInfo.rtmp.get, wholeRoomInfo.layout)
                      //                      ctx.self ! UpdateRTMP(liveInfo4Mix.liveId)
                    }
                    val audienceInfo = AudienceInfo(userId4Audience, userInfoOpt.get.userName, s"user-${userId4Audience}")
                    dispatch(RcvComment(-1l, "", s"user:$userId join in room:$roomId")) //群发评论
                    //                    dispatchTo(subscribers.keys.toList.filter(t => t._1 != wholeRoomInfo.roomInfo.userId && t._1 != userId4Audience), Join4AllRsp(Some("main"))) //除了host和连线者发送混流的liveId
                    //                    dispatchTo(List((wholeRoomInfo.roomInfo.userId, false)), AudienceJoinRsp(Some(audienceInfo)))
                    //                    dispatchTo(List((userId4Audience, false)), JoinRsp(Some(liveIdHost.get.liveId), Some(LiveInfo(s"user-${userId4Audience}"))))
                  } else {
                    log.debug(s"${ctx.self.path} 没有主播的liveId,roomId=$roomId")
                    dispatchTo(List((wholeRoomInfo.roomInfo.userId, false)), AudienceJoinError)

                  }
                case None =>
                  log.debug(s"${ctx.self.path} 没有主播的liveId,roomId=$roomId")
                  dispatchTo(List((wholeRoomInfo.roomInfo.userId, false)), AudienceJoinError)
              }
            } else {
              log.debug(s"${ctx.self.path} 错误的主播userId,可能是数据库里没有用户,userId=$userId4Audience")
              dispatchTo(List((wholeRoomInfo.roomInfo.userId, false)), AudienceJoinError)
            }
          }
        } else {
          dispatchTo(List((wholeRoomInfo.roomInfo.userId, false)), AudienceJoinRsp(None))
          dispatchTo(List((userId4Audience, false)), JoinRefused)
        }

        Behaviors.same

      case HostShutJoin(`roomId`) =>
        log.debug(s"${ctx.self.path} the host has shut the join in room$roomId ----ws")
        liveInfoMap.remove(Role.audience)
        liveInfoMap.get(Role.host) match {
          case Some(value) =>
            val liveIdHost = value.get(wholeRoomInfo.roomInfo.userId)
          //            if (liveIdHost.nonEmpty) {
          //              ctx.self ! UpdateRTMP(liveIdHost.get.liveId)
          //            }
          //            else {
          //              log.debug(s"${ctx.self.path} 没有主播的liveId,无法撤回主播流,roomId=$roomId")
          //              dispatchTo(List((wholeRoomInfo.roomInfo.userId, false)), AudienceJoinError)
          //            }
          case None =>
            log.debug(s"${ctx.self.path} 没有主播的liveInfo")
            dispatchTo(List((wholeRoomInfo.roomInfo.userId, false)), NoHostLiveInfoError)
        }
        ProcessorClient.closeRoom(roomId)
        //        dispatch(HostDisconnect(liveInfoMap(Role.host)(wholeRoomInfo.roomInfo.userId).liveId))
        dispatch(RcvComment(-1l, "", s"the host has shut the join in room $roomId"))
        Behaviors.same

      case ModifyRoomInfo(roomName, roomDes) =>
        val roomInfo = if (roomName.nonEmpty && roomDes.nonEmpty) {
          wholeRoomInfo.roomInfo.copy(roomName = roomName.get, roomDes = roomDes.get)
        } else if (roomName.nonEmpty) {
          wholeRoomInfo.roomInfo.copy(roomName = roomName.get)
          wholeRoomInfo.roomInfo.copy(roomName = roomName.get)
        } else if (roomDes.nonEmpty) {
          wholeRoomInfo.roomInfo.copy(roomDes = roomDes.get)
        } else {
          wholeRoomInfo.roomInfo
        }
        val info = WholeRoomInfo(roomInfo, wholeRoomInfo.layout)
        log.debug(s"${ctx.self.path} modify the room info$info  ---ws")
        dispatch(UpdateRoomInfo2Client(roomInfo.roomName, roomInfo.roomDes))
        dispatchTo(List((wholeRoomInfo.roomInfo.userId, false)), ModifyRoomRsp())
        idle(info, liveInfoMap, subscribers, startTime, invitationList, isJoinOpen)


      case HostStopPushStream(`roomId`) =>
        log.debug(s"${ctx.self.path} host stop stream in room${wholeRoomInfo.roomInfo.roomId},name=${wholeRoomInfo.roomInfo.roomName}   ---ws")
        //前端需要自行处理主播主动断流的情况，后台默认连线者也会断开
        dispatch(HostStopPushStream2Client)
        wholeRoomInfo.roomInfo.rtmp match {
          case Some(v) =>
            //            if (v != liveInfoMap(Role.host)(wholeRoomInfo.roomInfo.userId).liveId)
              ProcessorClient.closeRoom(roomId)
            log.debug(s"roomId:$roomId 主播停止推流，向distributor发送finishpull消息")
            DistributorClient.finishPull(v)
            if (startTime != -1l) {
              roomManager ! RoomManager.DelaySeekRecord(wholeRoomInfo, roomId, startTime, v, invitationList)
            }
          case None =>
        }
        liveInfoMap.clear()
        val newroomInfo = wholeRoomInfo.copy(roomInfo = wholeRoomInfo.roomInfo.copy(rtmp = None))
        log.debug(s"${ctx.self.path} 主播userId=${userId}已经停止推流，更新房间信息，liveId=${newroomInfo.roomInfo.rtmp}")
        subscribers.get((wholeRoomInfo.roomInfo.userId, false)) match {
          case Some(hostActor) =>
            idle(newroomInfo, liveInfoMap, mutable.HashMap((wholeRoomInfo.roomInfo.userId, false) -> hostActor), -1l, invitationList, isJoinOpen)
          case None =>
            idle(newroomInfo, liveInfoMap, mutable.HashMap.empty[(Long, Boolean), ActorRef[UserActor.Command]], -1l, invitationList, isJoinOpen)
        }

      case JoinReq(userId4Audience, `roomId`, clientType) =>
        if (isJoinOpen) {
          UserInfoDao.searchById(userId4Audience).map { r =>
            if (r.nonEmpty) {
              dispatchTo(List((wholeRoomInfo.roomInfo.userId, false)), AudienceJoin(userId4Audience, r.get.userName, clientType))
            } else {
              log.debug(s"${ctx.self.path} 连线请求失败，用户id错误id=$userId4Audience in roomId=$roomId")
              dispatchTo(List((userId4Audience, false)), JoinAccountError)
            }
          }.recover {
            case e: Exception =>
              log.debug(s"${ctx.self.path} 连线请求失败，内部错误error=$e")
              dispatchTo(List((userId4Audience, false)), JoinInternalError)
          }
        } else {
          dispatchTo(List((userId4Audience, false)), JoinInvalid)
        }
        Behaviors.same

      case AudienceShutJoin(`roomId`, audienceId) =>
        //切断所有的观众连线
        liveInfoMap.get(Role.audience) match {
          case Some(value) =>
            log.debug(s"${ctx.self.path} the audience connection has been shut")
            //            liveInfoMap.remove(Role.audience)
            liveInfoMap.get(Role.host) match {
              case Some(info) =>
                val liveIdHost = info.get(wholeRoomInfo.roomInfo.userId)
              //                if (liveIdHost.nonEmpty) {
              //                  ctx.self ! UpdateRTMP(liveIdHost.get.liveId)
              //                }
              //                else {
              //                  log.debug(s"${ctx.self.path} 没有主播的liveId,无法撤回主播流,roomId=$roomId")
              //                  dispatchTo(List((wholeRoomInfo.roomInfo.userId, false)), AudienceJoinError)
              //                }
              case None =>
                log.debug(s"${ctx.self.path} no host liveId")
            }
            ProcessorClient.userQuit(roomId, liveInfoMap(Role.audience)(audienceId).liveId, wholeRoomInfo.roomInfo.rtmp.get)
            liveInfoMap(Role.audience).remove(audienceId)
            //            dispatch(AuthProtocol.AudienceDisconnect(liveInfoMap(Role.host)(wholeRoomInfo.roomInfo.userId).liveId))
            dispatch(RcvComment(-1l, "", s"the audience has shut the join in room $roomId"))
          case None =>
            log.debug(s"${ctx.self.path} no audience liveId")
        }
        Behaviors.same

      //TODO 暂未使用，暂不修改，版本2019.10.23
      case AudienceShutJoinPlus(userId4Audience) =>
        //切换某个单一用户的连线
        liveInfoMap.get(Role.audience) match {
          case Some(value) =>
            value.get(userId4Audience) match {
              case Some(liveInfo) =>
                log.debug(s"${ctx.self.path} the audience connection has been shut")
                value.remove(userId4Audience)
                liveInfoMap.put(Role.audience, value)
                val liveList = liveInfoMap.toList.sortBy(_._1).flatMap(r => r._2).map(_._2.liveId)
                //ProcessorClient.updateRoomInfo(wholeRoomInfo.roomInfo.roomId, liveList, wholeRoomInfo.layout, wholeRoomInfo.aiMode, 0l)
                dispatch(AudienceDisconnect(liveInfoMap(Role.host)(wholeRoomInfo.roomInfo.userId).liveId))
                dispatch(RcvComment(-1l, "", s"the audience ${userId4Audience} has shut the join in room ${roomId}"))
              case None =>
                log.debug(s"${ctx.self.path} no audience liveId")
            }
          case None =>
            log.debug(s"${ctx.self.path} no audience liveId")
        }
        Behaviors.same

      case JudgeLike(`userId`, `roomId`) =>
        dispatchTo(List((userId, false)), JudgeLikeRsp(like = false))
        Behaviors.same

      case Comment(`userId`, `roomId`, comment, color, extension) =>
        UserInfoDao.searchById(userId).onComplete {
          case Success(value) =>
            value match {
              case Some(v) =>
                dispatch(RcvComment(userId, v.userName, comment, color, extension))
              case None =>
                log.debug(s"${ctx.self.path.name} the database doesn't have the user")
            }
            ctx.self ! SwitchBehavior("idle", idle(wholeRoomInfo, liveInfoMap, subscribers, startTime, invitationList, isJoinOpen))
          case Failure(e) =>
            log.debug(s"s${ctx.self.path.name} the search by userId error:$e")
            ctx.self ! SwitchBehavior("idle", idle(wholeRoomInfo, liveInfoMap, subscribers, startTime, invitationList, isJoinOpen))
        }
        switchBehavior(ctx, "busy", busy(), InitTime, TimeOut("busy"))

      case GetLiveIdReq(uId) =>
        log.info(s"ID:$uId get token req.")
        UserInfoDao.searchById(uId).map { r =>
          if (r.nonEmpty) {
            if (r.get.`sealed`) {
              dispatchTo(List((userId, false)), GetLiveIdRsp(None, 100038, "该用户已经被封号哦"))
            }
            else {
              val liveId = if (uId == wholeRoomInfo.roomInfo.userId) {
                liveInfoMap(Role.host)(uId).liveId
              } else {
                liveInfoMap(Role.audience)(uId).liveId
              }
              dispatchTo(List((userId, false)), GetLiveIdRsp(Some(liveId)))
            }
          }
          else {
            dispatchTo(List((userId, false)), GetLiveIdRsp(None, 100034, "该用户不存在"))
          }
        }.recover {
          case e: Exception =>
            log.debug(s"获取用户信息失败，inter error：$e")
            dispatchTo(List((userId, false)), GetLiveIdRsp(None, 100036, s"获取用户信息失败，inter error：$e"))
        }
        Behaviors.same

      case PingPackage =>
        Behaviors.same

      case x =>
        log.debug(s"${ctx.self.path} recv an unknown msg:$x")
        Behaviors.same
    }
  }

  private def changeMode(ctx: ActorContext[RoomActor.Command], anchorUid: Long, dispatchTo: (List[(Long, Boolean)], WsMsgRm) => Unit)(roomId: Long, liveIdList: List[String], screenLayout: Int, startTime: Long) = {
    ProcessorClient.updateRoomInfo(roomId, screenLayout).map {
      case Right(rsp) =>
        log.debug(s"${ctx.self.path} modify the mode success")
        dispatchTo(List((anchorUid, false)), ChangeModeRsp())
      case Left(error) =>
        log.debug(s"${ctx.self.path} there is some error:$error")
        dispatchTo(List((anchorUid, false)), ChangeModeError)
    }
  }

  private def dispatch(subscribers: mutable.HashMap[(Long, Boolean), ActorRef[UserActor.Command]])(msg: WsMsgRm)(implicit sendBuffer: MiddleBufferInJvm): Unit = {
    log.debug(s"${subscribers}分发消息：$msg")
    subscribers.values.foreach(_ ! UserActor.DispatchMsg(Wrap(msg.asInstanceOf[WsMsgRm].fillMiddleBuffer(sendBuffer).result()), msg.isInstanceOf[AuthProtocol.HostCloseRoom]))
  }

  /**
    * subscribers:所有的订阅者
    * targetUserIdList：要发送的目标用户
    * msg：发送的消息
    **/
  private def dispatchTo(subscribers: mutable.HashMap[(Long, Boolean), ActorRef[UserActor.Command]])(targetUserIdList: List[(Long, Boolean)], msg: WsMsgRm)(implicit sendBuffer: MiddleBufferInJvm): Unit = {
    log.debug(s"${subscribers}定向分发消息：$msg")
    //    log.info(s"----------------$subscribers")
    targetUserIdList.foreach { k =>
      subscribers.get(k).foreach { r =>
        r ! UserActor.DispatchMsg(
          Wrap(msg.asInstanceOf[WsMsgRm].fillMiddleBuffer(sendBuffer).result()),
          msg.isInstanceOf[AuthProtocol.HostCloseRoom]
        )
      }
    }
  }


}
