package example

import java.util.concurrent.Flow.{
  Processor, Subscriber, Subscription
}
import java.util.concurrent.SubmissionPublisher
import scala.collection.mutable.ListBuffer

object Hello extends App {

  //tryBasic()
  //tryProcessor()
  tryByName()


  def tryBasic(): Unit = {
    val publisher: SubmissionPublisher[String] = new SubmissionPublisher()
    val subscriber: EndSubscriber[String] = new EndSubscriber()
  
    publisher.subscribe(subscriber)
  
    val items: List[String] = List("1", "x", "2", "x", "3", "x")

    items.foreach(publisher.submit(_))

    publisher.close()
  
    Thread.sleep(2000)

    println(subscriber.consumedElements)
  }

  def tryProcessor(): Unit = {
    val publisher: SubmissionPublisher[String] = new SubmissionPublisher()
    val transformProcessor: TransformProcessor[String, Int] = new TransformProcessor(
      _.toInt
    )
    val subscriber: EndSubscriber[Int] = new EndSubscriber()
    val items: List[String] = List("1", "2", "3")
    
    publisher.subscribe(transformProcessor)
    transformProcessor.subscribe(subscriber)
    
    items.foreach(publisher.submit(_))
    publisher.close()
    
    Thread.sleep(2000)

    println(subscriber.consumedElements)
  }

  def tryByName(): Unit = {
    def sebuahFungsi(nomor: Int): String = {
      println("hiho")
      s"yeah $nomor"
    }

    val tp = new TransformProcessorX[Int, String](
      x => s"$x"
    )
    
    println(tp.doIt(5))

    println(tp.doThat(sebuahFungsi(7)))
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


class TransformProcessor[T, R](function: T => R) extends SubmissionPublisher[R] with Processor[T, R] {
  var subscription: Option[Subscription] = None
  
  def onSubscribe(subs: Subscription): Unit = {
    subscription = Some(subs)
    subscription.get.request(1)
  }
  
  def onNext(item: T): Unit = {
    submit(function(item))
    subscription.get.request(1)
  }

  def onError(error: Throwable): Unit = {
    error.printStackTrace()
  }

  def onComplete(): Unit = {
    close()
  }
}


class TransformProcessorX[T, R](fn: T => R) {
  def doIt(param: T): R = fn(param)
  def doThat(byName: => R): R = {
    println("apa ini")

    byName
  }
}
