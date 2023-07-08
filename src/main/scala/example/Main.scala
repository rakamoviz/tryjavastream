
package example

import java.util.concurrent.Flow.Subscription
import java.util.concurrent.SubmissionPublisher
import scala.collection.mutable.ListBuffer

object Main extends App {

  tryProcessor()

  def tryProcessor(): Unit = {
    val publisher: SubmissionPublisher[Int] = new SubmissionPublisher()
    val resourcePoolProcessor: StatefulProcessor[Int, String, Unit] = StatefulProcessor[Int, String, Unit] () {
      subscription => 
        InitialState(subscription)
    }
    val subscriber: EndSubscriber[String] = new EndSubscriber()
    val items: List[Int] = List(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
    
    publisher.subscribe(resourcePoolProcessor)
    resourcePoolProcessor.subscribe(subscriber)
    
    items.foreach(publisher.submit(_))
    publisher.close()
    
    Thread.sleep(2000)

    println(subscriber.consumedElements)
  }
}

case class InitialState(subscription: Subscription) extends StatefulProcessor.State[Int, String, Unit] {
  def handleItem(item: Int, context: Option[Unit]): StatefulProcessor.Result[Int, String, Unit] = {
    println("Im initial state")
    StatefulProcessor.Result(SubsequentState(subscription), s"item_$item", true)
  }
}

case class SubsequentState(subscription: Subscription) extends StatefulProcessor.State[Int, String, Unit] {
  def handleItem(item: Int, context: Option[Unit]): StatefulProcessor.Result[Int, String, Unit] = {
    println("Im subsequent state")
    StatefulProcessor.Result(this, s"item_$item", true)
  }
}