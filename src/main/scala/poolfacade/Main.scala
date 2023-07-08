package poolfacade

import java.util.concurrent.Flow.Subscription
import java.util.concurrent.SubmissionPublisher
import scala.collection.mutable.ListBuffer
import example._

object Main extends App {

  tryProcessor()

  def tryProcessor(): Unit = {
    val poolContext = PoolContext("poolName", "travelAgencyId", "iataCode")

    val publisher: SubmissionPublisher[PoolCommand] = new SubmissionPublisher()
    val resourcePoolProcessor = StatefulProcessor[PoolCommand, Option[CookieMap], PoolContext] (Some(poolContext)) {
      subscription => 
        InitialState(subscription)
    }
    val subscriber: EndSubscriber[Option[CookieMap]] = new EndSubscriber()
    val items: List[PoolCommand] = List(
        PoolCommand.Use,
        PoolCommand.Release
    )
    
    publisher.subscribe(resourcePoolProcessor)
    resourcePoolProcessor.subscribe(subscriber)
    
    items.foreach(publisher.submit(_))
    publisher.close()
    
    Thread.sleep(2000)

    println(subscriber.consumedElements)
  }
}

case class PoolContext(gds: String, travelAgencyId: String, iataCode: String)
trait PoolCommand
object PoolCommand {
    case object Use extends PoolCommand
    case object Release extends PoolCommand
}

case class CookieMap(payload: String)

case class InitialState(subscription: Subscription) extends StatefulProcessor.State[PoolCommand, Option[CookieMap], PoolContext] {
  def handleItem(command: PoolCommand, context: Option[PoolContext]): StatefulProcessor.Result[PoolCommand, Option[CookieMap], PoolContext] = {
    println("Im initial state")
    command match {
        case PoolCommand.Use => StatefulProcessor.Result(
            UsedState(subscription), Some(CookieMap("xyz")), true
        )
        case _ => StatefulProcessor.Result(
            this, None, true
        )
    }
  }
}

case class UsedState(subscription: Subscription) extends StatefulProcessor.State[PoolCommand, Option[CookieMap], PoolContext] {
  def handleItem(command: PoolCommand, context: Option[PoolContext]): StatefulProcessor.Result[PoolCommand, Option[CookieMap], PoolContext] = {
    println("UsedState")
    command match {
        case PoolCommand.Release => StatefulProcessor.Result(
            ReleasedState(subscription), None, false
        )
        case _ => StatefulProcessor.Result(
            this, None, true
        )
    }
  }
}

case class ReleasedState(subscription: Subscription) extends StatefulProcessor.State[PoolCommand, Option[CookieMap], PoolContext] {
  def handleItem(command: PoolCommand, context: Option[PoolContext]): StatefulProcessor.Result[PoolCommand, Option[CookieMap], PoolContext] = {
    println("ReleasedState")
    StatefulProcessor.Result(
        this, None, false
    )
  }
}
