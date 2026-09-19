package cloudflow.pekkostreamsdoc;

// tag::merge[]
import cloudflow.pekkostream.*;
import cloudflow.streamlets.*;
import cloudflow.streamlets.avro.*;
import cloudflow.pekkostream.javadsl.*;
import cloudflow.pekkostream.util.javadsl.*;
import org.apache.pekko.stream.javadsl.*;

import java.util.*;

public class DataMerge extends PekkoStreamlet {
  AvroInlet<Data> inlet1 = AvroInlet.<Data>create("in-0", Data.class);
  AvroInlet<Data> inlet2 = AvroInlet.<Data>create("in-1", Data.class);
  AvroOutlet<Data> outlet = AvroOutlet.<Data>create("out",  d -> d.getKey(), Data.class);

  public StreamletShape shape() {
    return StreamletShape.createWithInlets(inlet1, inlet2).withOutlets(outlet);
  }

  public RunnableGraphStreamletLogic createLogic() {
    return new RunnableGraphStreamletLogic(getContext()) {
      public RunnableGraph<?> createRunnableGraph() {
        return Merger.source(getContext(), inlet1, inlet2).to(getCommittableSink(outlet));
      }
    };
  }
}
// end::merge[]
