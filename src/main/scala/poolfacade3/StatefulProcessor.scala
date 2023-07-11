package poolfacade3

import scala.concurrent.{Future, Await}

import java.util.concurrent.Flow.{
  Processor, Subscriber, Subscription
}
import java.util.concurrent.SubmissionPublisher
import scala.concurrent.duration.Duration

sealed abstract case class StatefulProcessor[I, T, C](
  initializer: (Subscription) => StatefulProcessor.State[I, T, C],
  context: C
) 
  extends SubmissionPublisher[Option[T]] 
  with Processor[I, Option[T]] {

  var state: Option[StatefulProcessor.State[I, T, C]] = None
  implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global

  def onSubscribe(subscription: Subscription): Unit = {
    state match {
      case Some(_) => 
        throw new IllegalStateException("Already subscribed")
      case None =>
        state = Some(initializer(subscription))
        subscription.request(1) 
    }
  }
  
  def onNext(item: I): Unit = {
    state match {
      case Some(s) =>
        val result = Await.ready(s.handleItem(item, context), Duration.Inf).flatMap[Unit] { result => 
            submit(result.transformedItem)
            state = Some(result.nextState)

            if (!result.finished) {
                s.subscription.request(1)
            } 

            Future.unit
        }
      case None => 
        throw new IllegalStateException("Already subscribed")
    }
  } 

  def onError(error: Throwable): Unit = {
    error.printStackTrace()
  }

  def onComplete(): Unit = {
    close()
  }
}

object StatefulProcessor {
  trait State[I, +T, -C] { //try removing the - from C
    val subscription: Subscription

    def handleItem(item: I, context: C): Future[Result[I, T, C]]
  }

case class Result[I, +T, -C](nextState: State[I, T, C], transformedItem: Option[T], finished: Boolean)

  def apply[I, T, C](context: C)(initializer: (Subscription) => StatefulProcessor.State[I, T, C]): StatefulProcessor[I, T, C] =
    new StatefulProcessor[I, T, C] (initializer, context){}
}