/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

/*
 * Copyright (C) 2018-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package org.apache.pekko.persistence

import scala.concurrent.duration._

import com.typesafe.config.ConfigFactory

import org.apache.pekko
import pekko.actor.{ Actor, ActorLogging, ActorRef, Props }
import pekko.actor.Status.Failure
import pekko.persistence.journal.SteppingInmemJournal
import pekko.testkit.{ ImplicitSender, PekkoSpec, TestProbe }

object PersistentActorRecoveryTimeoutSpec {
  val journalId = "persistent-actor-recovery-timeout-spec"

  def config =
    SteppingInmemJournal
      .config(PersistentActorRecoveryTimeoutSpec.journalId)
      .withFallback(ConfigFactory.parseString("""
          |pekko.persistence.journal.stepping-inmem.recovery-event-timeout=1s
        """.stripMargin))
      .withFallback(PersistenceSpec.config("stepping-inmem", "PersistentActorRecoveryTimeoutSpec"))

  class TestActor(probe: ActorRef) extends NamedPersistentActor("recovery-timeout-actor") {
    override def receiveRecover: Receive = Actor.emptyBehavior

    override def receiveCommand: Receive = {
      case x =>
        persist(x) { _ =>
          sender() ! x
        }
    }

    override protected def onRecoveryFailure(cause: Throwable, event: Option[Any]): Unit = {
      probe ! Failure(cause)
    }
  }

  class TestReceiveTimeoutActor(receiveTimeout: FiniteDuration, probe: ActorRef)
      extends NamedPersistentActor("recovery-timeout-actor-2")
      with ActorLogging {

    override def preStart(): Unit = {
      context.setReceiveTimeout(receiveTimeout)
    }

    override def receiveRecover: Receive = {
      case RecoveryCompleted => probe ! context.receiveTimeout
      case _                 => // we don't care
    }

    override def receiveCommand: Receive = {
      case x =>
        persist(x) { _ =>
          sender() ! x
        }
    }

    override protected def onRecoveryFailure(cause: Throwable, event: Option[Any]): Unit = {
      log.error(cause, "Recovery of TestReceiveTimeoutActor failed")
      probe ! Failure(cause)
    }
  }

}

class PersistentActorRecoveryTimeoutSpec
    extends PekkoSpec(PersistentActorRecoveryTimeoutSpec.config)
    with ImplicitSender {

  import PersistentActorRecoveryTimeoutSpec.journalId

  "The recovery timeout" should {

    "fail recovery if timeout is not met when recovering" in {
      val probe = TestProbe()
      val persisting = system.actorOf(Props(classOf[PersistentActorRecoveryTimeoutSpec.TestActor], probe.ref))

      awaitAssert(SteppingInmemJournal.getRef(journalId), 3.seconds)
      val journal = SteppingInmemJournal.getRef(journalId)

      // initial read highest
      SteppingInmemJournal.step(journal)

      persisting ! "A"
      SteppingInmemJournal.step(journal)
      expectMsg("A")

      watch(persisting)
      system.stop(persisting)
      expectTerminated(persisting)

      // now replay, but don't give the journal any tokens to replay events
      // so that we cause the timeout to trigger
      val replaying = system.actorOf(Props(classOf[PersistentActorRecoveryTimeoutSpec.TestActor], probe.ref))
      watch(replaying)

      // initial read highest
      SteppingInmemJournal.step(journal)

      probe.expectMsgType[Failure].cause shouldBe a[RecoveryTimedOut]
      expectTerminated(replaying)

      // avoid having it stuck in the next test from the
      // last read request above
      SteppingInmemJournal.step(journal)
    }

    "should not interfere with receive timeouts" in {
      val timeout = 42.days

      val probe = TestProbe()
      val persisting =
        system.actorOf(Props(classOf[PersistentActorRecoveryTimeoutSpec.TestReceiveTimeoutActor], timeout, probe.ref))

      awaitAssert(SteppingInmemJournal.getRef(journalId), 3.seconds)
      val journal = SteppingInmemJournal.getRef(journalId)

      // initial read highest
      SteppingInmemJournal.step(journal)

      persisting ! "A"
      SteppingInmemJournal.step(journal)
      expectMsg("A")

      watch(persisting)
      system.stop(persisting)
      expectTerminated(persisting)

      // now replay, but don't give the journal any tokens to replay events
      // so that we cause the timeout to trigger
      system.actorOf(Props(classOf[PersistentActorRecoveryTimeoutSpec.TestReceiveTimeoutActor], timeout, probe.ref))

      // initial read highest
      SteppingInmemJournal.step(journal)

      // read journal
      SteppingInmemJournal.step(journal)

      // we should get initial receive timeout back from actor when replay completes
      probe.expectMsg(timeout)

    }

  }

}
