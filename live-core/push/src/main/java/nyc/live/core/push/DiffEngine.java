package nyc.live.core.push;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import nyc.live.core.state.Identified;

/** Computes the {@link Diff} between two full states using {@code equals}. */
public final class DiffEngine {

    private DiffEngine() {
    }

    public static <T extends Identified> Diff<T> compute(long seq, Instant generatedAt,
                                                          Map<String, T> previous, Map<String, T> next) {
        List<T> upserts = new ArrayList<>();
        for (Map.Entry<String, T> e : next.entrySet()) {
            if (!Objects.equals(previous.get(e.getKey()), e.getValue())) {
                upserts.add(e.getValue());
            }
        }
        List<String> removes = new ArrayList<>();
        for (String id : previous.keySet()) {
            if (!next.containsKey(id)) {
                removes.add(id);
            }
        }
        return new Diff<>(seq, generatedAt, upserts, removes);
    }

    /** Applies a diff to a mutable state map in place. */
    public static <T extends Identified> void apply(Map<String, T> state, Diff<T> diff) {
        for (String id : diff.removes()) {
            state.remove(id);
        }
        for (T t : diff.upserts()) {
            state.put(t.id(), t);
        }
    }
}
