package nyc.live.core.push;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/** Wire format of the push protocol (JSON over SSE). */
public final class PushMessages {

    private PushMessages() {
    }

    /** Sent once per connection, first. {@code seq} is the gateway's sequence at that moment. */
    public record Snapshot<T>(String type, long seq, Instant generatedAt, Instant sentAt, Collection<T> entities) {
        public static <T> Snapshot<T> of(long seq, Instant generatedAt, Instant sentAt, Collection<T> entities) {
            return new Snapshot<>("snapshot", seq, generatedAt, sentAt, List.copyOf(entities));
        }
    }

    /**
     * Sent every flush interval if anything changed. Clients must apply diffs in order; a gap in
     * {@code seq} means the client should reconnect to get a fresh snapshot.
     */
    public record DiffMessage<T>(String type, long seq, Instant generatedAt, Instant sentAt,
                                 List<T> upserts, List<String> removes) {
        public static <T> DiffMessage<T> of(Diff<T> d, Instant sentAt) {
            return new DiffMessage<>("diff", d.seq(), d.generatedAt(), sentAt, d.upserts(), d.removes());
        }
    }
}
