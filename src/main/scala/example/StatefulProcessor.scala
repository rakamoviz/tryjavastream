package example

import java.util.concurrent.Flow.{
  Processor, Subscriber, Subscription
}
import java.util.concurrent.SubmissionPublisher

sealed abstract case class StatefulProcessor[I, T](
  initializer: (Subscription) => StatefulProcessor.State[I, T]
) 
  extends SubmissionPublisher[T] 
  with Processor[I, T] {
  var state: Option[StatefulProcessor.State[I, T]] = None
  
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
        val result = s.handleItem(item)
        submit(result.transformedItem)
        state = Some(result.nextState)

        if (!result.finished) {
            s.subscription.request(1)
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
  trait State[I, T] {
    val subscription: Subscription

    def handleItem(item: I): Result[I, T]
  }

  case class Result[I, T](nextState: State[I, T], transformedItem: T, finished: Boolean)

  def apply[I, T](initializer: (Subscription) => StatefulProcessor.State[I, T]): StatefulProcessor[I, T] =
    new StatefulProcessor[I, T] (initializer){}
}