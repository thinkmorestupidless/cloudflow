package com.example;

import org.apache.pekko.NotUsed;
import org.apache.pekko.stream.*;
import org.apache.pekko.stream.javadsl.*;

import cloudflow.pekkostream.*;
import cloudflow.pekkostream.javadsl.*;
import cloudflow.streamlets.*;
import cloudflow.streamlets.avro.*;

//tag::processor[]
class TestProcessor extends PekkoStreamlet {
  AvroInlet<Data> inlet = AvroInlet.<Data>create("in", Data.class);
  AvroOutlet<Data> outlet = AvroOutlet.<Data>create("out", d -> Integer.toString(d.getId()), Data.class);

  public StreamletShape shape() {
    return StreamletShape.createWithInlets(inlet).withOutlets(outlet);
  }

  public PekkoStreamletLogic createLogic() {
    return new RunnableGraphStreamletLogic(getContext()) {
      public RunnableGraph<NotUsed> createRunnableGraph() {
        return getPlainSource(inlet)
          .via(Flow.<Data>create().filter(d -> d.getId() % 2 == 0))
          .to(getPlainSink(outlet));
      }
    };
  }
}
//end::processor[]