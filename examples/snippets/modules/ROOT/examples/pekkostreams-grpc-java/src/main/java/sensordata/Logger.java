package sensordata;

import org.apache.pekko.stream.javadsl.RunnableGraph;

import cloudflow.pekkostream.PekkoStreamlet;
import cloudflow.pekkostream.PekkoStreamletLogic;
import cloudflow.pekkostream.javadsl.RunnableGraphStreamletLogic;
import cloudflow.streamlets.CodecInlet;
import cloudflow.streamlets.StreamletShape;
import cloudflow.streamlets.proto.javadsl.ProtoInlet;

import java.util.Optional;
import sensordata.grpc.SensorData;

public class Logger extends PekkoStreamlet {
    private final ProtoInlet<SensorData> inlet = (ProtoInlet<SensorData>) ProtoInlet.create(
        "in", 
        SensorData.class,
        false,
        (inBytes, throwable) -> {
            context().system().log().error(String.format("an exception occurred on inlet: %s -> (hex string) %h", throwable.getMessage(), inBytes));
            return Optional.empty();
        }              
    );
    
    @Override
    public StreamletShape shape() {
        return StreamletShape.createWithInlets(inlet);
    }
    @Override
    public PekkoStreamletLogic createLogic() {
        return new RunnableGraphStreamletLogic(getContext()) {
            @Override
            public RunnableGraph<?> createRunnableGraph() {
                return getSourceWithCommittableContext(inlet)
                        .map(d -> {
                            System.out.println("Saw " + d);
                            return d;
                        })
                        .to(getCommittableSink());
            }
        };
    }


}
