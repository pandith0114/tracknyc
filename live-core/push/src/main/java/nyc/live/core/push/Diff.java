package nyc.live.core.push;

import java.time.Instant;
import java.util.List;

/**
 * Changes between two states: entities that are new or changed, and ids that disappeared.
 *
 * @param seq         producer sequence number (monotonic per producer)
 * @param generatedAt when the producer computed the newest state in this diff; used for push lag
 */
public record Diff<T>(long seq, Instant generatedAt, List<T> upserts, List<String> removes) {

    public Diff {
        upserts = List.copyOf(upserts);
        removes = List.copyOf(removes);
    }

    public boolean isEmpty() {
        return upserts.isEmpty() && removes.isEmpty();
    }

    public int size() {
        return upserts.size() + removes.size();
    }
}
