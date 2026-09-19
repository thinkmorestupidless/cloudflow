package cloudflow.pekkostreamsdoc;

// tag::httpIngress[]
import cloudflow.pekkostream.PekkoServerStreamlet;

import cloudflow.pekkostream.PekkoStreamletLogic;
import cloudflow.pekkostream.util.javadsl.HttpServerLogic;

import cloudflow.streamlets.RoundRobinPartitioner;
import cloudflow.streamlets.StreamletShape;
import cloudflow.streamlets.avro.AvroOutlet;

import org.apache.pekko.http.javadsl.marshallers.jackson.Jackson;

public class DataHttpIngress extends PekkoServerStreamlet {
  AvroOutlet<Data> out =
      AvroOutlet.<Data>create("out", Data.class)
          .withPartitioner(RoundRobinPartitioner.getInstance());

  public StreamletShape shape() {
    return StreamletShape.createWithOutlets(out);
  }

  public PekkoStreamletLogic createLogic() {
    return HttpServerLogic.createDefault(
        this, out, Jackson.byteStringUnmarshaller(Data.class), getContext());
  }
}
// end::httpIngress[]