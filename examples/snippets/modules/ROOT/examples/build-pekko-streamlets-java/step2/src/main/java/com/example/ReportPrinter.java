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
  // 3. TODO Override createLogic to provide StreamletLogic
  public RunnableGraphStreamletLogic createLogic() { throw new UnsupportedOperationException("Not Implemented"); }
}