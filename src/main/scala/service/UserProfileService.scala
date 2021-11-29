package service

import akka.actor.ActorRef
import kz.mounty.fm.amqp.messages.AMQPMessage
import kz.mounty.fm.amqp.messages.MountyMessages.{MountyApi, SpotifyGateway, UserProfileCore}
import kz.mounty.fm.domain.requests._
import kz.mounty.fm.domain.room.Room
import kz.mounty.fm.domain.user.{RoomUser, UserProfile}
import kz.mounty.fm.exceptions.{ErrorCodes, ErrorSeries, ExceptionInfo, ServerErrorRequestException}
import org.json4s.Formats
import org.json4s.jackson.Serialization._
import org.json4s.native.JsonMethods._
import org.mongodb.scala.MongoCollection
import org.mongodb.scala.bson.conversions.Bson
import org.mongodb.scala.model.Filters.equal
import org.mongodb.scala.model.Updates.set
import repositories.{RoomRepository, RoomUserRepository, UserProfileRepository}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

class UserProfileService(implicit userProfileCollection: MongoCollection[UserProfile],
                         roomUserCollection: MongoCollection[RoomUser],
                         roomCollection: MongoCollection[Room],
                         ex: ExecutionContext,
                         publisher: ActorRef,
                         formats: Formats
                        ) {
  val roomRepository = new RoomRepository()
  val roomUserRepository = new RoomUserRepository()
  val userProfileRepository = new UserProfileRepository()

  def createUserProfile(message: AMQPMessage): Unit = {
    message.routingKey match {
      case UserProfileCore.CreateUserProfileRequest.routingKey =>
        val requestEntity = parse(message.entity).extract[CreateUserProfileRequestBody]
        userProfileRepository.findByFilter[UserProfile](equal("id", requestEntity.tokenKey)).onComplete {
          case Success(value) =>
            value match {
              case Some(userProfile) =>
                val reply = write(CreateUserProfileResponseBody(userProfile))
                publisher ! message.copy(entity = reply, routingKey = MountyApi.CreateUserProfileResponse.routingKey, exchange = "X:mounty-api-out")
              case None =>
                val request = write(GetUserProfileGatewayRequestBody(requestEntity.tokenKey))
                publisher ! message.copy(entity = request, routingKey = SpotifyGateway.GetUserProfileGatewayRequest.routingKey, exchange =  "X:mounty-spotify-gateway-in")
            }
          case Failure(exception) =>
            val error = ServerErrorRequestException(
              ErrorCodes.INTERNAL_SERVER_ERROR(ErrorSeries.USER_PROFILE_CORE),
              Some(exception.getMessage)
            ).getExceptionInfo
            val reply = write(error)
            publisher ! message.copy(entity = reply, routingKey = MountyApi.Error.routingKey, exchange =  "X:mounty-api-out")
        }
      case UserProfileCore.GetUserProfileGatewayResponse.routingKey =>
        (for {
          body <- Future(parse(message.entity).extract[GetUserProfileGatewayResponseBody])
          result <- userProfileRepository.create[UserProfile](body.userProfile)
        } yield
          publisher ! message.copy(
            entity = write(CreateUserProfileResponseBody(result)),
            routingKey = MountyApi.CreateUserProfileResponse.routingKey,
            exchange = "X:mounty-api-out")
          ) recover {
          case exception: Throwable =>
            exception match {
              case _: org.json4s.MappingException =>
                val exceptionInfo = parse(message.entity).extract[ExceptionInfo]
                val reply = write(exceptionInfo)
                publisher ! message.copy(entity = reply, routingKey = MountyApi.Error.routingKey, exchange = "X:mounty-api-out")
              case _ =>
                val error = ServerErrorRequestException(
                  ErrorCodes.INTERNAL_SERVER_ERROR(ErrorSeries.USER_PROFILE_CORE),
                  Some(exception.getMessage)
                ).getExceptionInfo
                val reply = write(error)
                publisher ! message.copy(entity = reply, routingKey = MountyApi.Error.routingKey, exchange = "X:mounty-api-out")
            }
        }
    }
  }

  def updateUserProfile(message: AMQPMessage): Unit = {
    val requestEntity = parse(message.entity).extract[UpdateUserProfileRequestBody]
    var updatedBson: Seq[Bson] = Seq()
    if(requestEntity.name.isDefined) {
      updatedBson :+= set("name", requestEntity.name.get)
    }
    if(requestEntity.email.isDefined) {
      updatedBson :+= set("email", requestEntity.email.get)
    }
    if(requestEntity.avatarUrl.isDefined) {
      updatedBson :+= set("avatarUrl", requestEntity.avatarUrl.get)
    }
    if(requestEntity.spotifyUri.isDefined) {
      updatedBson :+= set("spotifyUri", requestEntity.spotifyUri.get)
    }

    if(updatedBson.nonEmpty) {
      userProfileRepository.updateOneByFilter[UserProfile](equal("id", requestEntity.id), updatedBson).onComplete {
        case Success(value) =>
          val reply = write(UpdateUserProfileResponseBody(value))
          publisher ! message.copy(entity = reply, routingKey = MountyApi.UpdateUserProfileResponse.routingKey, exchange =  "X:mounty-api-out")
        case Failure(exception) =>
          val error = ServerErrorRequestException(
            ErrorCodes.INTERNAL_SERVER_ERROR(ErrorSeries.USER_PROFILE_CORE),
            Some(exception.getMessage)
          ).getExceptionInfo
          val reply = write(error)
          publisher ! message.copy(entity = reply, routingKey = MountyApi.Error.routingKey, exchange =  "X:mounty-api-out")
      }
    } else {
      val reply = write(UpdateUserProfileResponseBody(false))
      publisher ! message.copy(entity = reply, routingKey = MountyApi.UpdateUserProfileResponse.routingKey, exchange =  "X:mounty-api-out")
    }
  }

  def deleteUserProfile(message: AMQPMessage): Unit = {
    val requestEntity = parse(message.entity).extract[DeleteUserProfileRequestBody]
    userProfileRepository.deleteOneByFilter[UserProfile](equal("id", requestEntity.id)).onComplete {
      case Success(value) =>
        val reply = write(DeleteUserProfileResponseBody(value))
        publisher ! message.copy(entity = reply, routingKey = MountyApi.DeleteUserProfileResponse.routingKey, exchange =  "X:mounty-api-out")
      case Failure(exception) =>
        val error = ServerErrorRequestException(
          ErrorCodes.INTERNAL_SERVER_ERROR(ErrorSeries.USER_PROFILE_CORE),
          Some(exception.getMessage)
        ).getExceptionInfo
        val reply = write(error)
        publisher ! message.copy(entity = reply, routingKey = MountyApi.Error.routingKey, exchange =  "X:mounty-api-out")
    }
  }

  def getUserProfileById(message: AMQPMessage): Unit = {
    val requestEntity = parse(message.entity).extract[GetUserProfileByIdRequestBody]
    userProfileRepository.findByFilter[UserProfile](equal("id", requestEntity.id)).onComplete {
      case Success(value) =>
        value match {
          case Some(value) =>
            val reply = write(CreateUserProfileResponseBody(value))
            publisher ! message.copy(entity = reply, routingKey = MountyApi.GetUserProfileByIdResponse.routingKey, exchange =  "X:mounty-api-out")
          case None =>
            val error = ServerErrorRequestException(
              ErrorCodes.INTERNAL_SERVER_ERROR(ErrorSeries.USER_PROFILE_CORE),
              Some("Entity not found")
            ).getExceptionInfo
            val reply = write(error)
            publisher ! message.copy(entity = reply, routingKey = MountyApi.Error.routingKey, exchange =  "X:mounty-api-out")
        }
      case Failure(exception) =>
        val error = ServerErrorRequestException(
          ErrorCodes.INTERNAL_SERVER_ERROR(ErrorSeries.USER_PROFILE_CORE),
          Some(exception.getMessage)
        ).getExceptionInfo
        val reply = write(error)
        publisher ! message.copy(entity = reply, routingKey = MountyApi.Error.routingKey, exchange =  "X:mounty-api-out")
    }
  }

  def getUserProfileRooms(message: AMQPMessage): Unit = {
    val requestEntity = parse(message.entity).extract[GetUserProfileByIdRequestBody]
    (for {
      roomUser <- roomUserRepository.findByFilter[RoomUser](equal("profileId", requestEntity.id))
      rooms <- roomRepository.findAllByFilter[Room](equal("id", roomUser.get.roomId))
    } yield {
      val reply = write(GetUserProfileRoomsResponseBody(rooms))
      publisher ! message.copy(entity = reply, routingKey = MountyApi.GetUserProfileRoomsResponse.routingKey, exchange =  "X:mounty-api-out")
    }).recover{
      case e: Throwable =>
        val error = ServerErrorRequestException(
          ErrorCodes.INTERNAL_SERVER_ERROR(ErrorSeries.USER_PROFILE_CORE),
          Some(e.getMessage)
        ).getExceptionInfo
        val reply = write(error)
        publisher ! message.copy(entity = reply, routingKey = MountyApi.Error.routingKey, exchange =  "X:mounty-api-out")
    }
  }
}
