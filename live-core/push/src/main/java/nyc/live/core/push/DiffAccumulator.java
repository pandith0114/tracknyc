package nyc.live.core.push;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import nyc.live.core.state.Identified;

/**
 * Coalesces several diffs into one, so the gateway can push "every N seconds" regardless of
 * how often producers publish. Later changes win; an upsert after a remove resurrects the
 * entity and vice versa. Not thread-safe; callers synchronise.
 */
public final class DiffAccumulator<T extends Identified> {

    private final Map<String, T> upserts = new LinkedHashMap<>();
    private final Set<String> removes = new LinkedHashSet<>();
    private Instant oldestGeneratedAt;
    private long newestSeq = -1;

    public void add(Diff<T> diff) {
        for (String id : diff.removes()) {
            upserts.remove(id);
            removes.add(id);
        }
        for (T t : diff.upserts()) {
            removes.remove(t.id());
            upserts.put(t.id(), t);
        }
        if (!diff.isEmpty() && (oldestGeneratedAt == null || diff.generatedAt().isBefore(oldestGeneratedAt))) {
            oldestGeneratedAt = diff.generatedAt();
        }
        newestSeq = Math.max(newestSeq, diff.seq());
    }

    public boolean isEmpty() {
        return upserts.isEmpty() && removes.isEmpty();
    }

    /** Returns the merged diff (renumbered with {@code seq}) and clears the accumulator. */
    public Optional<Diff<T>> drain(long seq) {
        if (isEmpty()) {
            return Optional.empty();
        }
        Diff<T> d = new Diff<>(seq, oldestGeneratedAt, new ArrayList<>(upserts.values()), new ArrayList<>(removes));
        upserts.clear();
        removes.clear();
        oldestGeneratedAt = null;
        return Optional.of(d);
    }

    public long newestSeq() {
        return newestSeq;
    }
}
