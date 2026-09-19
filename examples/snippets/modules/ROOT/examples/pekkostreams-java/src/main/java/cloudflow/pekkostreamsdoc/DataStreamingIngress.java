package cloudflow.pekkostreamsdoc;

// tag::httpStreamingIngress[]
import org.apache.pekko.http.javadsl.common.EntityStreamingSupport;
import org.apache.pekko.http.javadsl.marshallers.jackson.Jackson;

import cloudflow.pekkostream.PekkoServerStreamlet;

import cloudflow.pekkostream.PekkoStreamletLogic;
import cloudflow.pekkostream.util.javadsl.HttpServerLogic;
import cloudflow.streamlets.RoundRobinPartitioner;
import cloudflow.streamlets.StreamletShape;
import cloudflow.streamlets.avro.AvroOutlet;

public class DataStreamingIngress extends PekkoServerStreamlet {

  private AvroOutlet<Data> out =
      AvroOutlet.create("out", Data.class)
          .withPartitioner(RoundRobinPartitioner.getInstance());

  public StreamletShape shape() {
    return StreamletShape.createWithOutlets(out);
  }

  public PekkoStreamletLogic createLogic() {
    EntityStreamingSupport ess = EntityStreamingSupport.json();
    return HttpServerLogic.createDefaultStreaming(
        this, out, Jackson.byteStringUnmarshaller(Data.class), ess, getContext());
  }
}
// end::httpStreamingIngress[]