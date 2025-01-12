package org.seekloud.theia.webrtcServer.http

import akka.http.scaladsl.server
import akka.http.scaladsl.server.Directives.{complete, extractRequestContext}
import akka.http.scaladsl.server._
import akka.http.scaladsl.server.directives.BasicDirectives
import org.seekloud.theia.webrtcMessage.ptcl.{CommonErrorCode, ErrorRsp}
import org.seekloud.theia.webrtcServer.common.AppSettings
import org.seekloud.theia.webrtcServer.utils.{CirceSupport, SessionSupport}
import org.slf4j.LoggerFactory

/**
  * User: Taoz
  * Date: 12/4/2016
  * Time: 7:57 PM
  */

object SessionBase {
  private val logger = LoggerFactory.getLogger(this.getClass)

  private val sessionTimeout = 24 * 60 * 60 * 1000
  val SessionTypeKey = "STKey"

  object AdminSessionKey {
    val SESSION_TYPE = "medusa_adminSession"
    val aid = "medusa_aid"
    val name = "medusa_name"
    val loginTime = "medusa_loginTime"
  }

  object UserSessionKey {
    val SESSION_TYPE = "userSession"
    val playerId = "playerId"
    val playerName = "playerName"
    val timestamp = "timestamp"
  }

  case class AdminInfo (
                         aid: String,  //username
                         name: String  //password
                       )

  case class AdminSession(
                           adminInfo: AdminInfo,
                           time: Long
                         ){
    def toAdminSessionMap = {
      Map(
        SessionTypeKey -> AdminSessionKey.SESSION_TYPE,
        AdminSessionKey.aid -> adminInfo.aid,
        AdminSessionKey.name -> adminInfo.name,
        AdminSessionKey.loginTime -> time.toString
      )
    }
  }

  case class UserSession(
                          playerId: String,
                          playerName: String,
                          timestamp: String
                        ) {
    def toSessionMap = Map(
      SessionTypeKey -> UserSessionKey.SESSION_TYPE,
      UserSessionKey.playerId -> playerId,
      UserSessionKey.playerName -> playerName,
      UserSessionKey.timestamp -> timestamp
    )
  }

  implicit class SessionTransformer(sessionMap: Map[String, String]) {
    def toAdminSession: Option[AdminSession] = {
      logger.debug(s"toAdminSession: change map to session, ${sessionMap.mkString(",")}")
      try{
        if(sessionMap.get(SessionTypeKey).exists(_.equals(AdminSessionKey.SESSION_TYPE))){
          if(sessionMap(AdminSessionKey.loginTime).toLong - System.currentTimeMillis() > sessionTimeout){
            None
          } else{
            Some(AdminSession(
              AdminInfo(sessionMap(AdminSessionKey.aid),
                sessionMap(AdminSessionKey.name)
              ),
              sessionMap(AdminSessionKey.loginTime).toLong
            ))
          }
        } else{
          logger.debug("no session type in the session")
          None
        }
      } catch {
        case e: Exception =>
          e.printStackTrace()
          logger.warn(s"toAdminSession: ${e.getMessage}")
          None
      }
    }

    def toUserSession: Option[UserSession] = {
      logger.debug(s"toUserSession: change map to session, ${sessionMap.mkString(",")}")
      try {
        if (sessionMap.get(SessionTypeKey).exists(_.equals(UserSessionKey.SESSION_TYPE))) {
          Some(UserSession(
            sessionMap(UserSessionKey.playerId),
            sessionMap(UserSessionKey.playerName),
            sessionMap(UserSessionKey.timestamp)
          ))
        } else {
          logger.debug("no session type in the session")
          None
        }
      } catch {
        case e: Exception =>
          logger.warn(s"toUserSession: ${e.getMessage}")
          None
      }
    }
  }

}

trait SessionBase extends CirceSupport with SessionSupport {

  import SessionBase._
  import io.circe.generic.auto._

  override val sessionEncoder = SessionSupport.PlaySessionEncoder
  override val sessionConfig = AppSettings.sessionConfig

  //  def noSessionError(message:String = "no session") = ErrorRsp(1000102,s"$message")

  protected def setUserSession(userSession: UserSession): Directive0 = setSession(userSession.toSessionMap)

  def authUser(f: UserSession => server.Route) = optionalUserSession {
    case Some(session) =>
      f(session)
    case None =>
      complete(CommonErrorCode.noSessionError())
  }

  protected val optionalAdminSession: Directive1[Option[AdminSession]] = optionalSession.flatMap{
    case Right(sessionMap) => BasicDirectives.provide(sessionMap.toAdminSession)
    case Left(error) =>
      logger.debug(error)
      BasicDirectives.provide(None)
  }

  protected val optionalUserSession: Directive1[Option[UserSession]] = optionalSession.flatMap {
    case Right(sessionMap) => BasicDirectives.provide(sessionMap.toUserSession)
    case Left(error) =>
      logger.debug(error)
      BasicDirectives.provide(None)
  }
  def noSessionError(message:String = "no session") = ErrorRsp(1000102,s"$message")

  def loggingAction: Directive[Tuple1[RequestContext]] = extractRequestContext.map { ctx =>
    //    log.info(s"Access uri: ${ctx.request.uri} from ip ${ctx.request.uri.autheiaity.host.address}.")
    ctx
  }

}