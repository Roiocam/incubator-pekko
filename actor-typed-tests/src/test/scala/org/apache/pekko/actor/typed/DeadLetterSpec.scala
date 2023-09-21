/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

/*
 * Copyright (C) 2017-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package org.apache.pekko.actor.typed

import org.apache.pekko.actor.IllegalActorStateException
import org.apache.pekko.actor.testkit.typed.scaladsl.{ LogCapturing, ScalaTestWithActorTestKit }
import org.apache.pekko.actor.typed.scaladsl.AskPattern.{ schedulerFromActorSystem, Askable }
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.Behaviors._
import org.apache.pekko.util.Timeout
import org.scalatest.wordspec.AnyWordSpecLike

import java.util.concurrent.CountDownLatch
import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future, TimeoutException }
import scala.util.{ Failure, Success }

trait Command

case class Multiply(a: Int, b: Int, forwardRef: ActorRef[WorkerCommand], replyTo: ActorRef[Int]) extends Command
case class ReplyResult(num: Int, replyTo: ActorRef[Int]) extends Command
case class Ignore() extends Command

trait WorkerCommand

case class WorkerMultiply(a: Int, b: Int, replyTo: ActorRef[WorkerResult]) extends WorkerCommand

case class WorkerResult(num: Int) extends WorkerCommand

class ManualTerminatedTestSetup(workerCnt: Int) {
  implicit val timeout: Timeout = 10.millis
  val workerLatch = new CountDownLatch(workerCnt)

  def forwardBehavior: Behavior[Command] =
    setup[Command] { context =>
      Behaviors.receiveMessage[Command] { msg =>
        msg match {
          case Multiply(a, b, ref, replyTo) =>
            // context.ask is asynchronous
            context.ask[WorkerCommand, WorkerResult](ref, resultReply => WorkerMultiply(a, b, resultReply)) {
              case Success(result) => ReplyResult(result.num, replyTo)
              case Failure(_)      => Ignore()
            }
            Behaviors.same
          case ReplyResult(num, replyTo) =>
            replyTo ! num
            Behaviors.same
          case Ignore() => Behaviors.same
        }
      }
    }

  def workerBehavior: Receive[WorkerCommand] =
    Behaviors.receiveMessage[WorkerCommand] { msg =>
      msg match {
        case WorkerMultiply(a, b, replyTo) =>
          workerLatch.await()
          replyTo ! WorkerResult(a * b)
          Behaviors.stopped
        case _ =>
          throw IllegalActorStateException("worker actor should not receive other message.")
      }
    }
}

class DeadLetterSpec extends ScalaTestWithActorTestKit(
      """
    pekko.loglevel=DEBUG
    pekko.actor.debug.event-stream = on
    """) with AnyWordSpecLike with LogCapturing {

  implicit def executor: ExecutionContext =
    system.executionContext

  "DeadLetterActor" must {

    "publish dead letter with recipient when context.ask terminated" in new ManualTerminatedTestSetup(workerCnt = 1) {
      val deadLetterProbe = createDeadLetterProbe()
      val forwardRef = spawn(forwardBehavior)
      val workerRef = spawn(workerBehavior)

      // this not completed unit worker reply.
      val multiplyResult: Future[Int] = forwardRef.ask(replyTo => Multiply(3, 9, workerRef, replyTo))
      // waiting for temporary ask actor terminated with timeout
      val result = multiplyResult.failed.futureValue
      result shouldBe a[TimeoutException]
      result.getMessage should startWith("Ask timed out on")
      // unlock worker reply
      workerLatch.countDown()

      val deadLetter = deadLetterProbe.receiveMessage()
      deadLetter.message shouldBe a[WorkerResult]
      val deadLettersRef = system.classicSystem.deadLetters
      // that should be not equals, otherwise, it may raise confusion, perform like a dead letter sent to the deadLetterActor.
      deadLetter.recipient shouldNot equal(deadLettersRef)
    }

    "publish dead letter with recipient when AskPattern timeout" in new ManualTerminatedTestSetup(workerCnt = 1) {
      val deadLetterProbe = createDeadLetterProbe()
      val workerRef = spawn(workerBehavior)

      // this not completed unit countDown.
      val multiplyResult: Future[WorkerResult] = workerRef.ask(replyTo => WorkerMultiply(3, 9, replyTo))
      // waiting for temporary ask actor terminated with timeout
      val result = multiplyResult.failed.futureValue
      result shouldBe a[TimeoutException]
      result.getMessage should startWith("Ask timed out on")
      // unlock worker reply
      workerLatch.countDown()

      val deadLetter = deadLetterProbe.receiveMessage()
      deadLetter.message shouldBe a[WorkerResult]
      val deadLettersRef = system.classicSystem.deadLetters
      // that should be not equals, otherwise, it may raise confusion, perform like a dead letter sent to the deadLetterActor.
      deadLetter.recipient shouldNot equal(deadLettersRef)
    }
  }
}
