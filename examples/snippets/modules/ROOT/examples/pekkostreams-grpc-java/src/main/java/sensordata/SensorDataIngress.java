package sensordata;

//tag::logic[]
import org.apache.pekko.grpc.javadsl.ServerReflection;
//end::logic[]
import org.apache.pekko.http.javadsl.model.HttpRequest;
import org.apache.pekko.http.javadsl.model.HttpResponse;
import org.apache.pekko.japi.function.Function;

import cloudflow.pekkostream.*;
//tag::logic[]
import cloudflow.pekkostream.util.javadsl.GrpcServerLogic;
//end::logic[]
import cloudflow.streamlets.*;
import cloudflow.streamlets.proto.javadsl.ProtoOutlet;

import sensordata.grpc.SensorData;
import sensordata.grpc.SensorDataService;
//tag::logic[]
import sensordata.grpc.SensorDataServiceHandlerFactory;

//end::logic[]

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletionStage;

//tag::logic[]
public class SensorDataIngress extends PekkoServerStreamlet {
    // ...

//end::logic[]
    public final ProtoOutlet<SensorData> out =
            new ProtoOutlet<SensorData>("out", RoundRobinPartitioner.getInstance(), SensorData.class);

    public StreamletShape shape() {
        return StreamletShape.createWithOutlets(out);
    }

    //tag::logic[]
    public PekkoStreamletLogic createLogic() {
        return new GrpcServerLogic(this, getContext()) {
            public List<Function<HttpRequest, CompletionStage<HttpResponse>>> handlers() {
                return Arrays.asList(
                        SensorDataServiceHandlerFactory.partial(new SensorDataServiceImpl(sinkRef(out)), SensorDataService.name, system()),
                        ServerReflection.create(Arrays.asList(SensorDataService.description), system()));
            }
        };
    }
}
//end::logic[]
