package nyc.tracknyc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import nyc.tracknyc.gtfs.GtfsSource;
import nyc.tracknyc.gtfs.GtfsStaticReader;
import nyc.tracknyc.network.NetworkModelBuilder;
import nyc.tracknyc.network.SystemNetwork;

/** Locates the repository's synthetic fixtures (set by Gradle as -Dtracknyc.fixtures-dir). */
public final class Fixtures {

    private Fixtures() {
    }

    public static Path dir() {
        String p = System.getProperty("tracknyc.fixtures-dir");
        Path path = p != null ? Path.of(p) : Path.of("fixtures");
        if (!Files.isDirectory(path)) {
            path = Path.of("..", "fixtures");
        }
        return path;
    }

    public static SystemNetwork network(String system) {
        NetworkModelBuilder b = new NetworkModelBuilder(system, ZoneId.of("America/New_York"));
        try {
            GtfsStaticReader.read(GtfsSource.of(dir().resolve("gtfs").resolve(system)), b);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return b.build();
    }

    public static byte[] realtime(String feedId, int snapshot) {
        try {
            return Files.readAllBytes(dir().resolve("realtime").resolve(feedId).resolve(String.format("%04d.pb", snapshot)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
