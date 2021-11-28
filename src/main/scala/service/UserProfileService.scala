package service

import akka.actor.ActorRef
import kz.mounty.fm.amqp.messages.AMQPMessage
import kz.mounty.fm.amqp.messages.MountyMessages.MountyApi
import kz.mounty.fm.domain.requests._
import kz.mounty.fm.domain.room.Room
import kz.mounty.fm.domain.user.{RoomUser, RoomUserType, UserProfile}
import kz.mounty.fm.exceptions.{ErrorCodes, ErrorSeries, ServerErrorRequestException}
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
    val requestEntity = parse(message.entity).extract[CreateUserProfileRequestBody]
    userProfileRepository.create(requestEntity.userProfile).onComplete {
      case Success(value) =>
        val reply = write(CreateUserProfileResponseBody(value))
        publisher ! message.copy(entity = reply, routingKey = MountyApi.CreateUserProfileResponse.routingKey)
      case Failure(exception) =>
        val error = ServerErrorRequestException(
          ErrorCodes.INTERNAL_SERVER_ERROR(ErrorSeries.USER_PROFILE_CORE),
          Some(exception.getMessage)
        )
        val reply = write(error)
        publisher ! message.copy(entity = reply, routingKey = MountyApi.Error.routingKey)
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
          publisher ! message.copy(entity = reply, routingKey = MountyApi.UpdateUserProfileResponse.routingKey)
        case Failure(exception) =>
          val error = ServerErrorRequestException(
            ErrorCodes.INTERNAL_SERVER_ERROR(ErrorSeries.USER_PROFILE_CORE),
            Some(exception.getMessage)
          )
          val reply = write(error)
          publisher ! message.copy(entity = reply, routingKey = MountyApi.Error.routingKey)
      }
    } else {
      val reply = write(UpdateUserProfileResponseBody(false))
      publisher ! message.copy(entity = reply, routingKey = MountyApi.UpdateUserProfileResponse.routingKey)
    }
  }

  def deleteUserProfile(message: AMQPMessage): Unit = {
    val requestEntity = parse(message.entity).extract[DeleteUserProfileRequestBody]
    userProfileRepository.deleteOneByFilter[UserProfile](equal("id", requestEntity.id)).onComplete {
      case Success(value) =>
        val reply = write(DeleteUserProfileResponseBody(value))
        publisher ! message.copy(entity = reply, routingKey = MountyApi.DeleteUserProfileResponse.routingKey)
      case Failure(exception) =>
        val error = ServerErrorRequestException(
          ErrorCodes.INTERNAL_SERVER_ERROR(ErrorSeries.USER_PROFILE_CORE),
          Some(exception.getMessage)
        )
        val reply = write(error)
        publisher ! message.copy(entity = reply, routingKey = MountyApi.Error.routingKey)
    }
  }

  def getUserProfileById(message: AMQPMessage): Unit = {
    val requestEntity = parse(message.entity).extract[GetUserProfileByIdRequestBody]
    userProfileRepository.findByFilter[UserProfile](equal("id", requestEntity.id)).onComplete {
      case Success(value) =>
        value match {
          case Some(value) =>
            val reply = write(CreateUserProfileResponseBody(value))
            publisher ! message.copy(entity = reply, routingKey = MountyApi.GetUserProfileByIdResponse.routingKey)
          case None =>
            val error = ServerErrorRequestException(
              ErrorCodes.INTERNAL_SERVER_ERROR(ErrorSeries.USER_PROFILE_CORE),
              Some("Entity not found")
            )
            val reply = write(error)
            publisher ! message.copy(entity = reply, routingKey = MountyApi.Error.routingKey)
        }
      case Failure(exception) =>
        val error = ServerErrorRequestException(
          ErrorCodes.INTERNAL_SERVER_ERROR(ErrorSeries.USER_PROFILE_CORE),
          Some(exception.getMessage)
        )
        val reply = write(error)
        publisher ! message.copy(entity = reply, routingKey = MountyApi.Error.routingKey)
    }
  }

  def getUserProfileRooms(message: AMQPMessage): Unit = {
    val requestEntity = parse(message.entity).extract[GetUserProfileByIdRequestBody]
    (for {
      roomUser <- roomUserRepository.findByFilter[RoomUser](equal("profileId", requestEntity.id))
      rooms <- roomRepository.findAllByFilter[Room](equal("id", roomUser.get.roomId))
    } yield {
      val reply = write(GetUserProfileRoomsResponseBody(rooms))
      publisher ! message.copy(entity = reply, routingKey = MountyApi.GetUserProfileRoomsResponse.routingKey)
    })
  }
}
