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

case class PoolContext[S <: Secret](
    resourcePool: ResourcePool[S],
    travelAgencyId: String,
    iataCode: String
)
trait PoolCommand
object PoolCommand {
  case object Use extends PoolCommand
  case object Release extends PoolCommand
}

trait Secret
case class CookieMap(payload: String) extends Secret
case class Token(payload: String) extends Secret

trait Disposable {
  def dispose(): Future[Unit]
}

trait UsableResource[+S <: Secret] extends Disposable {
  // here it's also called job
  def use[X](job: S => Future[X]): Future[X]
}

case class LocalUsableResource[+S <: Secret](secret: S)
    extends UsableResource[S] {
  override def use[X](job: S => Future[X]): Future[X] = {
    job(secret)
  }

  override def dispose(): Future[Unit] = {
    println(s"disposing $secret")
    Future.unit
  }
}

case class DestroyUnused[+S <: Secret](delegate: UsableResource[S])
    extends UsableResource[S] {
  override def use[X](job: S => Future[X]): Future[X] = {
    implicit val ec: scala.concurrent.ExecutionContext =
      scala.concurrent.ExecutionContext.global
    delegate.use(job).map { x => 
      dispose() 
      x
    }
  }

  override def dispose(): Future[Unit] = delegate.dispose()
}

trait ResourcePool[+S <: Secret] { // try removing + from the S here and see what happens
  type SecretType <: S

  // here it's called "job"
  def reserve[X](job: UsableResource[S] => Future[X]): Future[X]
}

class BspPool extends ResourcePool[CookieMap] { // LocalResourcePool
  override type SecretType = CookieMap
  override def reserve[X](
      job: UsableResource[CookieMap] => Future[X]
  ): Future[X] = {
    val bspResource = LocalUsableResource[CookieMap](CookieMap("abc"))
    val usableResource = DestroyUnused(bspResource)
    job(usableResource)
  }
}

case class InitialState[S <: Secret](subscription: Subscription)
    extends StatefulProcessor.State[PoolCommand, Option[S], PoolContext[S]] {
  def handleItem(command: PoolCommand, context: Option[PoolContext[S]]): Future[
    StatefulProcessor.Result[PoolCommand, Option[S], PoolContext[S]]
  ] = {
    implicit val ec: scala.concurrent.ExecutionContext =
      scala.concurrent.ExecutionContext.global
    println("Im in Initial state")
    command match {
      case PoolCommand.Use =>
        println("Received Use Command")
        context match {
          case Some(c) => {
            // https://www.baeldung.com/scala/futures-promises
            val actualJobCompletion = Promise[Unit]() // done
            val got = Promise[S]()

            c.resourcePool.reserve[Unit] { resource: UsableResource[S] =>
              resource.use[Unit] { secret: S =>
                got.success(secret)
                actualJobCompletion.future
              }
            }

            got.future.map { secret =>
              StatefulProcessor.Result(
                UsingState[S](subscription, actualJobCompletion),
                Some(secret),
                false
              )
            }
          }
          case _ => throw new AssertionError("context must exist")
        }
      case _ =>
        Future.successful(
          StatefulProcessor.Result(
            this,
            None,
            true
          )
        )
    }
  }
}

case class UsingState[S <: Secret](
    subscription: Subscription,
    actualJobCompletion: Promise[Unit]
) extends StatefulProcessor.State[PoolCommand, Option[S], PoolContext[S]] {
  def handleItem(command: PoolCommand, context: Option[PoolContext[S]]): Future[
    StatefulProcessor.Result[PoolCommand, Option[S], PoolContext[S]]
  ] = {
    implicit val ec: scala.concurrent.ExecutionContext =
      scala.concurrent.ExecutionContext.global
    println("Im in Using state")
    command match {
      case PoolCommand.Release =>
        println("Received Release Command")
        context match {
          case Some(c) => {
            actualJobCompletion.success(())
            Future.successful(
              StatefulProcessor.Result(
                this,
                None,
                false
              )
            )
          }
          case _ => throw new AssertionError("context must exist")
        }
      case _ => Future.successful(
          StatefulProcessor.Result(
            this,
            None,
            true
          )
        )
    }
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