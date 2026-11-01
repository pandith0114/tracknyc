package nyc.tracknyc.app;

import java.util.Map;
import nyc.tracknyc.position.TrainState;

/** Read access to the newest computed trains (implemented by {@link LiveTrainService}). */
@FunctionalInterface
public interface TrainSnapshots {
    Map<String, TrainState> trains();
}
