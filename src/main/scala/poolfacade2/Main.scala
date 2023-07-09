package poolfacade2

import java.util.concurrent.Flow.{
  Subscription, Subscriber
}
import java.util.concurrent.SubmissionPublisher
import scala.collection.mutable.ListBuffer
import scala.concurrent.{Future, Promise, Await}
import scala.concurrent.duration.Duration

object Main extends App {
  tryProcessor()

  def tryProcessor(): Unit = {
    val resourcePool = new BspPool()
    // val resourcePool = new AmadeusPool()

    val poolContext = PoolContext[resourcePool.SecretType](
      resourcePool,
      "travelAgencyId",
      "iataCode"
    )

    
    val publisher: SubmissionPublisher[PoolCommand] = new SubmissionPublisher()
    val resourcePoolProcessor = StatefulProcessor[PoolCommand, Option[resourcePool.SecretType], PoolContext[resourcePool.SecretType]] (Some(poolContext)) {
      subscription =>
        InitialState(subscription)
    }
    val subscriber: EndSubscriber[Option[resourcePool.SecretType]] = new EndSubscriber()

    publisher.subscribe(resourcePoolProcessor)
    resourcePoolProcessor.subscribe(subscriber)

    publisher.submit(PoolCommand.Use)
    Thread.sleep(1000)
    publisher.submit(PoolCommand.Release)
    Thread.sleep(1000)
    publisher.close()

    Thread.sleep(2000)

    println(subscriber.consumedElements)
  }
}

class EndSubscriber[T] extends Subscriber[T] {
  val consumedElements: ListBuffer[T] = ListBuffer()
  var subscription: Option[Subscription] = None

  def onSubscribe(subs: Subscription): Unit = {
    subscription = Some(subs)
    subscription.get.request(1)
  }
  
  def onNext(item: T): Unit = {
    println(s"Got: ${item}")
    consumedElements += item
    subscription.get.request(1)
  }

  def onError(error: Throwable): Unit = {
    error.printStackTrace()
  }

  def onComplete(): Unit = {
    println("Done")
  }
}