/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

/*
 * Copyright (C) 2014-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package org.apache.pekko.stream.io

import scala.concurrent.Await
import scala.concurrent.duration._

import org.apache.pekko
import pekko.stream.Attributes
import pekko.stream.ThrottleMode
import pekko.stream.impl.io.ByteStringParser
import pekko.stream.impl.io.ByteStringParser.ByteReader
import pekko.stream.impl.io.ByteStringParser.ParseResult
import pekko.stream.impl.io.ByteStringParser.ParseStep
import pekko.stream.scaladsl.Sink
import pekko.stream.scaladsl.Source
import pekko.stream.stage.GraphStageLogic
import pekko.stream.testkit.StreamSpec
import pekko.stream.testkit.TestPublisher
import pekko.stream.testkit.TestSubscriber
import pekko.util.ByteString

class ByteStringParserSpec extends StreamSpec {

  "ByteStringParser" must {

    "respect backpressure" in {
      class Chunker extends ByteStringParser[ByteString] {
        import ByteStringParser._

        override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new ParsingLogic {

          lazy val step: ParseStep[ByteString] = new ParseStep[ByteString] {
            override def parse(reader: ByteReader): ParseResult[ByteString] = {
              val bytes = reader.take(2)
              ParseResult(Some(bytes), step)
            }
          }

          startWith(step)

        }

      }

      // The Chunker produces two frames for one incoming 4 byte chunk. Hence, the rate in the incoming
      // side of the Chunker should only be half than on its outgoing side.

      val result = Source
        .repeat(ByteString("abcd"))
        .take(500)
        .throttle(1000, 1.second, 10, ThrottleMode.Enforcing)
        .via(new Chunker)
        .throttle(1000, 1.second, 10, ThrottleMode.Shaping)
        .runWith(Sink.ignore)

      Await.result(result, 5.seconds)

    }

    "continue parsing with multistep parsing logic if step doesn't produce element" in {
      object MultistepParsing extends ByteStringParser[ByteString] {
        def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new ParsingLogic {
          object ReadHeader extends ParseStep[ByteString] {
            def parse(reader: ByteReader): ParseResult[ByteString] = {
              require(reader.readShortBE() == 0xCAFE, "Magic header bytes not found")
              ParseResult(None, ReadData)
            }
          }
          object ReadData extends ParseStep[ByteString] {
            def parse(reader: ByteReader): ParseResult[ByteString] =
              ParseResult(Some(reader.takeAll()), ReadData)
          }

          startWith(ReadHeader)
        }
      }

      def run(data: ByteString*): ByteString =
        Await.result(
          Source[ByteString](data.toVector).via(MultistepParsing).fold(ByteString.empty)(_ ++ _).runWith(Sink.head),
          5.seconds)

      run(ByteString(0xCA), ByteString(0xFE), ByteString(0xEF, 0x12)) shouldEqual ByteString(0xEF, 0x12)
      run(ByteString(0xCA), ByteString(0xFE, 0xEF, 0x12)) shouldEqual ByteString(0xEF, 0x12)
      run(ByteString(0xCA, 0xFE), ByteString(0xEF, 0x12)) shouldEqual ByteString(0xEF, 0x12)
      run(ByteString(0xCA, 0xFE, 0xEF, 0x12)) shouldEqual ByteString(0xEF, 0x12)
    }

    "don't spin when logic is flawed" in {
      object SpinningLogic extends ByteStringParser[Int] {
        def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new ParsingLogic {
          object State1 extends ParseStep[Int] {
            def parse(reader: ByteReader): ParseResult[Int] =
              ParseResult(None, State2)
          }
          object State2 extends ParseStep[Int] {
            def parse(reader: ByteReader): ParseResult[Int] =
              ParseResult(None, State1)
          }

          startWith(State1)

          override protected def recursionLimit: Int = 10 // fail even earlier than the default
        }
      }

      (the[IllegalStateException] thrownBy Await.result(
        Source.single(ByteString("abc")).via(SpinningLogic).runWith(Sink.ignore),
        3.seconds)).getMessage shouldBe "Parsing logic didn't produce result after 10 steps. " +
      "Aborting processing to avoid infinite cycles. In the unlikely case that the parsing logic needs more recursion, " +
      "override ParsingLogic.recursionLimit."
    }

    "complete eagerly" in {
      object DummyParser extends ByteStringParser[ByteString] {
        def createLogic(inheritedAttributes: Attributes) = new ParsingLogic {
          startWith(new ParseStep[ByteString] {
            def parse(reader: ByteReader) = ParseResult(Some(reader.takeAll()), this)
          })
        }
      }

      val in = TestPublisher.probe[ByteString]()
      val out = TestSubscriber.probe[ByteString]()
      Source.fromPublisher(in).via(DummyParser).runWith(Sink.fromSubscriber(out))

      out.request(1L)
      in.expectRequest()
      in.sendNext(ByteString("aha!"))
      out.expectNext()
      // no new pull
      in.sendComplete()
      out.expectComplete()
    }

    "fail eagerly on truncation" in {
      object DummyParser extends ByteStringParser[ByteString] {
        def createLogic(inheritedAttributes: Attributes) = new ParsingLogic {
          startWith(new ParseStep[ByteString] {
            // take more data than there is in first chunk
            def parse(reader: ByteReader) = ParseResult(Some(reader.take(5)), this, false)
          })
        }
      }

      val in = TestPublisher.probe[ByteString]()
      val out = TestSubscriber.probe[ByteString]()
      Source.fromPublisher(in).via(DummyParser).runWith(Sink.fromSubscriber(out))

      out.request(1L)
      in.expectRequest()
      in.sendNext(ByteString("aha!"))
      out.expectNoMessage(100.millis)
      // no new pull
      in.sendComplete()
      out.expectError() shouldBe an[IllegalStateException]
    }
  }

}
