package poolfacade

import java.util.concurrent.Flow.Subscription
import java.util.concurrent.SubmissionPublisher
import scala.collection.mutable.ListBuffer
import example._

object Main extends App {

  tryProcessor()

  def tryProcessor(): Unit = {
    val resourcePool = new BspPool()
    //val resourcePool = new AmadeusPool()

    val poolContext = PoolContext[resourcePool.ResourceType](resourcePool, "travelAgencyId", "iataCode")

    val publisher: SubmissionPublisher[PoolCommand] = new SubmissionPublisher()
    val resourcePoolProcessor = StatefulProcessor[PoolCommand, Option[resourcePool.ResourceType], PoolContext[resourcePool.ResourceType]] (Some(poolContext)) {
      subscription => 
        InitialState(subscription)
    }
    val subscriber: EndSubscriber[Option[resourcePool.ResourceType]] = new EndSubscriber()
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

case class PoolContext[R <: Resource](resourcePool: ResourcePool[R], travelAgencyId: String, iataCode: String)
trait PoolCommand
object PoolCommand {
    case object Use extends PoolCommand
    case object Release extends PoolCommand
}

trait Resource
case class CookieMap(payload: String) extends Resource
case class Token(payload: String) extends Resource

trait ResourcePool[R <: Resource] {
    type ResourceType = R

    def reserve(): R
    def use(): Unit
    def unreserve(): Unit
}

class BspPool extends ResourcePool[CookieMap] {
    override def reserve(): CookieMap = {
        CookieMap("abc")
    }
    override def use(): Unit = {}
    override def unreserve(): Unit = {}
}

class AmadeusPool extends ResourcePool[Token] {
    override def reserve(): Token = {
        Token("abc")
    }
    override def use(): Unit = {}
    override def unreserve(): Unit = {}
}

case class InitialState[R <: Resource](subscription: Subscription) extends StatefulProcessor.State[PoolCommand, Option[R], PoolContext[R]] {
  def handleItem(command: PoolCommand, context: Option[PoolContext[R]]): StatefulProcessor.Result[PoolCommand, Option[R], PoolContext[R]] = {
    println("Im initial state")
    command match {
        case PoolCommand.Use => 
            context match {
                case Some(c) => StatefulProcessor.Result(
                    UsedState(subscription), Some(c.resourcePool.reserve()), true
                )
                case _ => StatefulProcessor.Result(
                    this, None, true
                )
            }
        case _ => StatefulProcessor.Result(
            this, None, true
        )
    }
  }
}

case class UsedState[R <: Resource](subscription: Subscription) extends StatefulProcessor.State[PoolCommand, Option[R], PoolContext[R]] {
  def handleItem(command: PoolCommand, context: Option[PoolContext[R]]): StatefulProcessor.Result[PoolCommand, Option[R], PoolContext[R]] = {
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

case class ReleasedState[R <: Resource](subscription: Subscription) extends StatefulProcessor.State[PoolCommand, Option[R], PoolContext[R]] {
  def handleItem(command: PoolCommand, context: Option[PoolContext[R]]): StatefulProcessor.Result[PoolCommand, Option[R], PoolContext[R]] = {
    println("ReleasedState")
    StatefulProcessor.Result(
        this, None, false
    )
  }
}
