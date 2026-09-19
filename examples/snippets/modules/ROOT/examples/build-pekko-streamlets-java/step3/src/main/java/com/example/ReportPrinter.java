package com.example;

import org.apache.pekko.NotUsed;
import org.apache.pekko.stream.*;
import org.apache.pekko.stream.javadsl.*;

import cloudflow.streamlets.*;
import cloudflow.streamlets.avro.*;
import cloudflow.pekkostream.*;
import cloudflow.pekkostream.javadsl.*;

public class ReportPrinter extends PekkoStreamlet {
  // 1. Create inlets and outlets
  AvroInlet<Report> inlet = AvroInlet.<Report>create("report-in", Report.class);

  // 2. Define the shape of the streamlet
  public StreamletShape shape() {
    return StreamletShape.createWithInlets(inlet);
  }
  // 3. Override createLogic to provide StreamletLogic
  public RunnableGraphStreamletLogic createLogic() {
    return new RunnableGraphStreamletLogic(getContext()) {
      public String format(Report report) {
        return report.getName() + "\n\n" +report.getDescription();
      }
      public RunnableGraph<NotUsed> createRunnableGraph() {
        return getPlainSource(inlet).to(Sink.foreach(report -> System.out.println(format(report))));
      }
    };
  }
}