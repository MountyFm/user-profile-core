package actors

import akka.actor.{Actor, ActorLogging, Props}
import com.rabbitmq.client.Channel
import kz.mounty.fm.amqp.AmqpPublisher
import kz.mounty.fm.amqp.messages.AMQPMessage
import kz.mounty.fm.serializers.Serializers

import scala.util.{Failure, Success}

object AmqpPublisherActor {
  def props(channel: Channel, exchange: String): Props =
    Props(new AmqpPublisherActor(channel, exchange))
}
class AmqpPublisherActor(channel: Channel, exchange: String)
  extends Actor
    with ActorLogging
    with Serializers{
  override def receive: Receive = {
    case message: AMQPMessage =>
      log.info(s"sending message: $message to AMQP")

      AmqpPublisher.publish(
        message,
        channel,
        exchange
      ) match {
        case Success(value) => log.info(s"succefully send message $message")
        case Failure(exception) => log.warning(s"couldn't message ${exception.getMessage}")

      }
  }
}