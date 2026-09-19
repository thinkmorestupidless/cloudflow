package com.example;

import org.apache.pekko.japi.Pair;
import org.junit.*;

import org.apache.pekko.NotUsed;
import org.apache.pekko.actor.*;
import org.apache.pekko.stream.*;
import org.apache.pekko.stream.javadsl.*;
import org.apache.pekko.testkit.*;

import cloudflow.streamlets.*;
import cloudflow.pekkostream.*;
import cloudflow.pekkostream.javadsl.*;

import cloudflow.pekkostream.testkit.javadsl.*;

import scala.concurrent.duration.Duration;

class TestProcessorTest {

  static ActorMaterializer mat;
  static ActorSystem system;

  @BeforeClass
  public static void setUp() throws Exception {
    system = ActorSystem.create();
    mat = ActorMaterializer.create(system);
  }

  @AfterClass
  public static void tearDown() throws Exception {
    TestKit.shutdownActorSystem(system, Duration.create(10, "seconds"), false);
    system = null;
  }

  @Test
  public void testFlowProcessor() {

    //tag::config-value[]
    PekkoStreamletTestKit testkit = PekkoStreamletTestKit.create(system).withConfigParameterValues(ConfigParameterValue.create(RecordSumFlow.recordsInWindowParameter, "20"));
    //end::config-value[]
  
  }

}
