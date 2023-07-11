package poolfacade3

import java.util.concurrent.Flow.{
  Subscription, Subscriber
}
import java.util.concurrent.SubmissionPublisher
import scala.collection.mutable.ListBuffer
import scala.concurrent.{Future, Promise}
import scala.concurrent.duration.Duration

trait PoolCommand
object PoolCommand {
  case object Reserve extends PoolCommand
  case object Use extends PoolCommand
  case object UseFinished extends PoolCommand
  case object ReserveFinished extends PoolCommand
}

trait Disposable {
  def dispose(): Future[Unit]
}

case class CookieMap(cookieStr: String)

trait UsableResource[+S] extends Disposable {
  // here it's also called job
  def use[X](task: S => Future[X]): Future[X] //in aleron it's called job. bad confusing name
}

case class LocalUsableResource[+S](secret: S)
    extends UsableResource[S] {
  override def use[X](task: S => Future[X]): Future[X] = {
    task(secret)
  }

  override def dispose(): Future[Unit] = {
    println(s"disposing $secret")
    Future.unit
  }
}

case class DestroyUnused[+S](delegate: UsableResource[S])
    extends UsableResource[S] {
  override def use[X](task: S => Future[X]): Future[X] = {
    implicit val ec: scala.concurrent.ExecutionContext =
      scala.concurrent.ExecutionContext.global
    delegate.use(task).map { x => 
      dispose() 
      x
    }
  }

  override def dispose(): Future[Unit] = delegate.dispose()
}

trait ResourcePool[+R] {
  // here it's called "job"
  // here R is actually UsableResource[S]
  def reserve[X](seriesOfTasks: R => Future[X]): Future[X]
}

class BspPool extends ResourcePool[UsableResource[CookieMap]] { // LocalResourcePool
  override def reserve[X](
      seriesOfTasks: UsableResource[CookieMap] => Future[X] //in aleron code it's called job. bad, confusing name.
  ): Future[X] = {
    val bspResource = LocalUsableResource[CookieMap](CookieMap("abc"))
    val usableResource = DestroyUnused(bspResource)
    seriesOfTasks(usableResource)
  }
}

case class UnreservedState[S](subscription: Subscription)
    extends StatefulProcessor.State[PoolCommand, S, ResourcePool[UsableResource[S]]] {
  def handleItem(command: PoolCommand, resourcePool: ResourcePool[UsableResource[S]]): Future[
    StatefulProcessor.Result[PoolCommand, S, ResourcePool[UsableResource[S]]]
  ] = {
    implicit val ec: scala.concurrent.ExecutionContext =
      scala.concurrent.ExecutionContext.global
    println("Im in Unreserved state")
    command match {
      case PoolCommand.Reserve =>
        println("Received Reserve Command")
        // https://www.baeldung.com/scala/futures-promises
        val actualSeriesOfTaskCompletion = Promise[Unit]() // done
        val got = Promise[(S, UsableResource[S])]()

        resourcePool.reserve[Unit] { resource: UsableResource[S] =>
          resource.use[Unit] { secret: S =>
            got.success((secret, resource))
            actualSeriesOfTaskCompletion.future.map { 
              _ => println("actualSeriesOfTaskCompletion completed")
            }
          }
        }

        got.future.map {
          case (secret, resource) =>
          StatefulProcessor.Result(
            ReservedState[S](subscription, resource, actualSeriesOfTaskCompletion),
            Some(secret),
            false
          )
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

case class ReservedState[S](
    subscription: Subscription,
    resource: UsableResource[S],
    actualSeriesOfTaskCompletion: Promise[Unit]
) extends StatefulProcessor.State[PoolCommand, S, ResourcePool[UsableResource[S]]] {
  def handleItem(command: PoolCommand, resourcePool: ResourcePool[UsableResource[S]]): Future[
    StatefulProcessor.Result[PoolCommand, S, ResourcePool[UsableResource[S]]]
  ] = {
    implicit val ec: scala.concurrent.ExecutionContext =
      scala.concurrent.ExecutionContext.global
    println("Im in Reserved state")
    command match {
      case PoolCommand.Use =>
        println("Received Use Command")

        val actualTaskCompletion = Promise[Unit]() // done
        val got = Promise[S]()

        resource.use[Unit] { secret: S =>
          got.success(secret)
          actualTaskCompletion.future.map { 
            _ => println("actualTaskCompletion completed")
          }
        }

        got.future.map {secret =>
          StatefulProcessor.Result(
            UsingState[S](
              subscription, resource, 
              actualSeriesOfTaskCompletion, actualTaskCompletion
            ),
            Some(secret),
            false
          )
        }
      case PoolCommand.ReserveFinished =>
        println("Received ReserveFinished Command")

        actualSeriesOfTaskCompletion.success(())
        Future.successful(
          StatefulProcessor.Result(
            ReleasedState[S](subscription),
            None,
            true
          )
        ) 
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

case class UsingState[S](
    subscription: Subscription,
    resource: UsableResource[S],
    actualSeriesOfTaskCompletion: Promise[Unit],
    actualTaskCompletion: Promise[Unit]
) extends StatefulProcessor.State[PoolCommand, S, ResourcePool[UsableResource[S]]] {
  def handleItem(command: PoolCommand, resourcePool: ResourcePool[UsableResource[S]]): Future[
    StatefulProcessor.Result[PoolCommand, S, ResourcePool[UsableResource[S]]]
  ] = {
    implicit val ec: scala.concurrent.ExecutionContext =
      scala.concurrent.ExecutionContext.global
    println("Im in Using state")
    command match {
      case PoolCommand.UseFinished =>
        println("Received UseFinished Command")
        actualTaskCompletion.success(())
        Future.successful(
          StatefulProcessor.Result(
            ReservedState[S](subscription, resource, actualSeriesOfTaskCompletion),
            None,
            false
          )
        )
      case PoolCommand.ReserveFinished =>
        println("Received ReserveFinished Command")

        actualTaskCompletion.success(())
        actualSeriesOfTaskCompletion.success(())
        Future.successful(
          StatefulProcessor.Result(
            ReleasedState[S](subscription),
            None,
            true
          )
        )       
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

case class ReleasedState[S](
    subscription: Subscription
) extends StatefulProcessor.State[PoolCommand, S, ResourcePool[UsableResource[S]]] {
  def handleItem(command: PoolCommand, resourcePool: ResourcePool[UsableResource[S]]): Future[
    StatefulProcessor.Result[PoolCommand, S, ResourcePool[UsableResource[S]]]
  ] = {
    implicit val ec: scala.concurrent.ExecutionContext =
      scala.concurrent.ExecutionContext.global
    println("Im in ReleasedState state")
    Future.successful(StatefulProcessor.Result(
      this,
      None,
      true
    ))
  }
}