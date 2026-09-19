package com.example;

import org.apache.pekko.NotUsed;
import org.apache.pekko.stream.*;
import org.apache.pekko.stream.javadsl.*;

import cloudflow.streamlets.*;
import cloudflow.streamlets.avro.*;
import cloudflow.pekkostream.*;
import cloudflow.pekkostream.javadsl.*;

public class ReportPrinter extends PekkoStreamlet {
  // 1. TODO Create inlets and outlets
  // 2. TODO Define the shape of the streamlet
  public StreamletShape shape() { throw new UnsupportedOperationException("Not Implemented"); }
  // 3. TODO Override createLogic to provide StreamletLogic
  public RunnableGraphStreamletLogic createLogic() { throw new UnsupportedOperationException("Not Implemented"); }
}