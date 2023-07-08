package example

import java.util.concurrent.Flow.{
  Processor, Subscriber, Subscription
}
import java.util.concurrent.SubmissionPublisher

sealed abstract case class StatefulProcessor[I, T, C](
  initializer: (Subscription) => StatefulProcessor.State[I, T, C],
  context: Option[C]
) 
  extends SubmissionPublisher[T] 
  with Processor[I, T] {
  var state: Option[StatefulProcessor.State[I, T, C]] = None
  
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
        val result = s.handleItem(item, context)
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
  trait State[I, T, C] {
    val subscription: Subscription

    def handleItem(item: I, context: Option[C]): Result[I, T, C]
  }

  case class Result[I, T, C](nextState: State[I, T, C], transformedItem: T, finished: Boolean)

  def apply[I, T, C](context: Option[C] = None)(initializer: (Subscription) => StatefulProcessor.State[I, T, C]): StatefulProcessor[I, T, C] =
    new StatefulProcessor[I, T, C] (initializer, context){}
}