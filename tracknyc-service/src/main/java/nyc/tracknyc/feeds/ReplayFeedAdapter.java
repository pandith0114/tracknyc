package nyc.tracknyc.feeds;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import nyc.live.core.feed.FeedAdapter;
import nyc.live.core.feed.FeedFetchException;
import nyc.live.core.feed.FeedSnapshot;

/**
 * Plays a recorded feed (one protobuf file per snapshot) on a loop at real-time speed. It
 * implements the same {@link FeedAdapter} contract as the live adapters, so everything
 * downstream — retries, health, decoding, positions, push — runs exactly as in production.
 *
 * <p>Timestamps are not rewritten. Instead each snapshot reports a {@code clockOffset} (feed
 * time minus wall time) and the engine reasons in feed time; outputs are converted back to wall
 * time. This keeps the recording byte-for-byte authentic (ADR 0004).
 *
 * <p>Directory layout: {@code manifest.properties} (recorded_start_epoch, loop_seconds) and
 * {@code index.csv} ({@code offset_seconds,file[,header_timestamp]}).
 */
public final class ReplayFeedAdapter implements FeedAdapter {

    record Entry(long offsetSeconds, String file) {
    }

    private final String id;
    private final String system;
    private final Path dir;
    private final Duration pollInterval;
    private final Clock clock;
    private final Instant recordedStart;
    private final long loopSeconds;
    private final List<Entry> entries;
    private final Instant playbackStart;
    private final Map<String, byte[]> cache = new ConcurrentHashMap<>();
    private final boolean synthetic;

    public ReplayFeedAdapter(String id, String system, Path dir, Duration pollInterval, Clock clock) {
        this.id = id;
        this.system = system;
        this.dir = dir;
        this.pollInterval = pollInterval;
        this.clock = clock;
        Properties manifest = new Properties();
        try (InputStream in = Files.newInputStream(dir.resolve("manifest.properties"))) {
            manifest.load(in);
            this.entries = readIndex(dir.resolve("index.csv"));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read replay recording in " + dir.toAbsolutePath(), e);
        }
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("replay recording " + dir + " has no snapshots");
        }
        this.recordedStart = Instant.ofEpochSecond(Long.parseLong(manifest.getProperty("recorded_start_epoch").trim()));
        long last = entries.get(entries.size() - 1).offsetSeconds();
        this.loopSeconds = Long.parseLong(manifest.getProperty("loop_seconds", Long.toString(last + 30)).trim());
        this.synthetic = Boolean.parseBoolean(manifest.getProperty("synthetic", "false").trim());
        // Align playback to the wall clock's minute so restarts land on similar positions.
        Instant now = clock.instant();
        this.playbackStart = now.minusSeconds(now.getEpochSecond() % 60).minusNanos(now.getNano());
    }

    static List<Entry> readIndex(Path index) throws IOException {
        List<Entry> out = new ArrayList<>();
        for (String line : Files.readAllLines(index)) {
            String l = line.trim();
            if (l.isEmpty() || l.startsWith("offset_seconds") || l.startsWith("#")) {
                continue;
            }
            String[] parts = l.split(",");
            out.add(new Entry(Long.parseLong(parts[0].trim()), parts[1].trim()));
        }
        out.sort((a, b) -> Long.compare(a.offsetSeconds(), b.offsetSeconds()));
        return out;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String system() {
        return system;
    }

    @Override
    public Duration pollInterval() {
        return pollInterval;
    }

    @Override
    public boolean replay() {
        return true;
    }

    public boolean synthetic() {
        return synthetic;
    }

    /** Seconds into the current loop for a wall-clock instant. */
    long positionInLoop(Instant wall) {
        long elapsed = Math.max(0, Duration.between(playbackStart, wall).toSeconds());
        return elapsed % loopSeconds;
    }

    Entry entryAt(long positionSeconds) {
        Entry chosen = entries.get(0);
        for (Entry e : entries) {
            if (e.offsetSeconds() <= positionSeconds) {
                chosen = e;
            } else {
                break;
            }
        }
        return chosen;
    }

    @Override
    public FeedSnapshot fetch() throws FeedFetchException {
        Instant now = clock.instant();
        long pos = positionInLoop(now);
        Entry e = entryAt(pos);
        byte[] bytes;
        try {
            bytes = cache.computeIfAbsent(e.file(), f -> {
                try {
                    return Files.readAllBytes(dir.resolve(f));
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            });
        } catch (UncheckedIOException ex) {
            throw FeedFetchException.permanent("replay file " + e.file() + " unreadable", ex);
        }
        Instant feedNow = recordedStart.plusSeconds(pos).plusNanos(now.getNano());
        return new FeedSnapshot(id, bytes, now, Duration.between(now, feedNow));
    }
}
