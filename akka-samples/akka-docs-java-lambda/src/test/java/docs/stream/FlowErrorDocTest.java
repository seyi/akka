/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.stream;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.concurrent.duration.Duration;
import scala.runtime.BoxedUnit;
import akka.actor.ActorSystem;
import akka.stream.ActorFlowMaterializer;
import akka.stream.ActorFlowMaterializerSettings;
import akka.stream.FlowMaterializer;
import akka.stream.Supervision;
import akka.stream.javadsl.OperationAttributes;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import akka.stream.javadsl.japi.Function;
import akka.testkit.JavaTestKit;

public class FlowErrorDocTest {

  private static ActorSystem system;

  @BeforeClass
  public static void setup() {
      system = ActorSystem.create("FlowDocTest");
  }

  @AfterClass
  public static void tearDown() {
      JavaTestKit.shutdownActorSystem(system);
      system = null;
  }
  
  @Test(expected = ArithmeticException.class)
  public void demonstrateFailStream() throws Exception {
    //#stop
    final FlowMaterializer mat = ActorFlowMaterializer.create(system);
    final Source<Integer, BoxedUnit> source = Source.from(Arrays.asList(0, 1, 2, 3, 4, 5))
      .map(elem -> 100 / elem);
    final Sink<Integer, Future<Integer>> fold =
      Sink.fold(0, (acc, elem) -> acc + elem);
    final Future<Integer> result = source.runWith(fold, mat);
    // division by zero will fail the stream and the
    // result here will be a Future completed with Failure(ArithmeticException)
    //#stop

    Await.result(result, Duration.create(3, TimeUnit.SECONDS));
  }

  @Test
  public void demonstrateResumeStream() throws Exception {
    //#resume
    final Function<Throwable, Supervision.Directive> decider = exc -> {
      if (exc instanceof ArithmeticException)
        return Supervision.resume();
      else
        return Supervision.stop();
    };
    final FlowMaterializer mat = ActorFlowMaterializer.create(
      ActorFlowMaterializerSettings.create(system).withSupervisionStrategy(decider),
      system);
    final Source<Integer, BoxedUnit> source = Source.from(Arrays.asList(0, 1, 2, 3, 4, 5))
      .map(elem -> 100 / elem);
    final Sink<Integer, Future<Integer>> fold =
      Sink.fold(0, (acc, elem) -> acc + elem);
    final Future<Integer> result = source.runWith(fold, mat);
    // the element causing division by zero will be dropped
    // result here will be a Future completed with Success(228)
    //#resume

    assertEquals(Integer.valueOf(228), Await.result(result, Duration.create(3, TimeUnit.SECONDS)));
  }

  @Test
  public void demonstrateResumeSectionStream() throws Exception {
    //#resume-section
    final FlowMaterializer mat = ActorFlowMaterializer.create(system);
    final Function<Throwable, Supervision.Directive> decider = exc -> {
      if (exc instanceof ArithmeticException)
        return Supervision.resume();
      else
        return Supervision.stop();
    };
    final Source<Integer, BoxedUnit> source = Source.from(Arrays.asList(0, 1, 2, 3, 4, 5))
      .section(OperationAttributes.supervisionStrategy(decider), 
        flow -> flow.filter(elem -> 100 / elem < 50).map(elem -> 100 / (5 - elem)));
    final Sink<Integer, Future<Integer>> fold =
      Sink.fold(0, (acc, elem) -> acc + elem);
    final Future<Integer> result = source.runWith(fold, mat);
    // the elements causing division by zero will be dropped
    // result here will be a Future completed with Success(150)
    //#resume-section

    assertEquals(Integer.valueOf(150), Await.result(result, Duration.create(3, TimeUnit.SECONDS)));
  }

  @Test
  public void demonstrateRestartSectionStream() throws Exception {
    //#restart-section
    final FlowMaterializer mat = ActorFlowMaterializer.create(system);
    final Function<Throwable, Supervision.Directive> decider = exc -> {
      if (exc instanceof IllegalArgumentException)
        return Supervision.restart();
      else
        return Supervision.stop();
    };
    final Source<Integer, BoxedUnit> source = Source.from(Arrays.asList(1, 3, -1, 5, 7))
      .section(OperationAttributes.supervisionStrategy(decider), 
        flow -> flow.scan(0, (acc, elem) -> {
          if (elem < 0) throw new IllegalArgumentException("negative not allowed");
          else return acc + elem;
        }));
    final Future<List<Integer>> result = source.grouped(1000)
      .runWith(Sink.<List<Integer>>head(), mat);
    // the negative element cause the scan stage to be restarted,
    // i.e. start from 0 again
    // result here will be a Future completed with Success(List(0, 1, 0, 5, 12))
    //#restart-section
    
    assertEquals(
      Arrays.asList(0, 1, 0, 5, 12), 
      Await.result(result, Duration.create(3, TimeUnit.SECONDS)));
  }

}