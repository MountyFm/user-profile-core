package actors

import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props}
import akka.util.Timeout
import kz.mounty.fm.amqp.messages.MountyMessages.{MountyApi, UserProfileCore}
import kz.mounty.fm.amqp.messages.{AMQPMessage, MountyMessages}
import kz.mounty.fm.serializers.Serializers
import org.json4s.native.JsonMethods.parse
import service.UserProfileService

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt


object AmqpListenerActor {
  def props()(implicit system: ActorSystem, ex: ExecutionContext, publisher: ActorRef, userProfileService: UserProfileService): Props =
    Props(new AmqpListenerActor())
}

class AmqpListenerActor(implicit system: ActorSystem, ex: ExecutionContext, publisher: ActorRef, userProfileService: UserProfileService)
  extends Actor
    with ActorLogging
    with Serializers {
  implicit val timeout: Timeout = 5.seconds

  override def receive: Receive = {
    case message: String =>
      log.info(s"received message $message")
      val amqpMessage = parse(message).extract[AMQPMessage]

      amqpMessage.routingKey match {
        case UserProfileCore.Ping.routingKey =>
          publisher ! AMQPMessage(amqpMessage.entity, MountyApi.Pong.routingKey, amqpMessage.actorPath, "X:mounty-api-out" )
        case UserProfileCore.CreateUserProfileRequest.routingKey =>
          userProfileService.createUserProfile(amqpMessage)
        case UserProfileCore.UpdateUserProfileRequest.routingKey =>
          userProfileService.updateUserProfile(amqpMessage)
        case UserProfileCore.DeleteUserProfileRequest.routingKey =>
          userProfileService.deleteUserProfile(amqpMessage)
        case UserProfileCore.GetUserProfileByIdRequest.routingKey =>
          userProfileService.getUserProfileById(amqpMessage)
        case UserProfileCore.GetUserProfileRoomsRequest.routingKey =>
          userProfileService.getUserProfileRooms(amqpMessage)
        case UserProfileCore.GetUserProfileGatewayResponse.routingKey =>
          userProfileService.createUserProfile(amqpMessage)
        case _ =>
          log.info("something else")
      }
  }
}
