package nyc.tracknyc.gtfs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import nyc.tracknyc.Fixtures;
import nyc.tracknyc.network.NetworkModelBuilder;
import nyc.tracknyc.network.SystemNetwork;
import org.junit.jupiter.api.Test;

/** The real MTA feeds arrive as .zip (often with a top-level folder); both forms must load. */
class GtfsZipTest {

    private static Path zipUpFixture(String system, String prefix) throws IOException {
        Path dir = Fixtures.dir().resolve("gtfs").resolve(system);
        Path zip = Files.createTempFile("tracknyc-" + system, ".zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (Path f : Files.list(dir).filter(p -> p.toString().endsWith(".txt")).toList()) {
                out.putNextEntry(new ZipEntry(prefix + f.getFileName()));
                Files.copy(f, out);
                out.closeEntry();
            }
        }
        zip.toFile().deleteOnExit();
        return zip;
    }

    private static SystemNetwork load(GtfsSource source) throws IOException {
        NetworkModelBuilder b = new NetworkModelBuilder("subway", ZoneId.of("America/New_York"))
                .fingerprint(source.fingerprint());
        GtfsStaticReader.read(source, b);
        return b.build();
    }

    @Test
    void loadsFromZipWithAndWithoutATopLevelFolder() throws IOException {
        SystemNetwork fromDir = Fixtures.network("subway");
        for (String prefix : new String[] {"", "gtfs_subway/"}) {
            GtfsSource zip = GtfsSource.of(zipUpFixture("subway", prefix));
            assertTrue(zip.isZip());
            SystemNetwork net = load(zip);
            assertEquals(fromDir.trips().size(), net.trips().size(), "prefix '" + prefix + "'");
            assertEquals(fromDir.stations().size(), net.stations().size());
            assertEquals(fromDir.segments().size(), net.segments().size());
        }
    }

    @Test
    void fingerprintChangesWithContent() throws IOException {
        GtfsSource a = GtfsSource.of(zipUpFixture("subway", ""));
        GtfsSource b = GtfsSource.of(zipUpFixture("subway", ""));
        assertEquals(a.fingerprint(), b.fingerprint(), "same content, same fingerprint");
        assertNotEquals(a.fingerprint(), GtfsSource.of(zipUpFixture("lirr", "")).fingerprint());
        // Opening the same entry twice must work: the stream owns and releases the zip handle.
        assertTrue(a.open("stops.txt").isPresent());
        assertTrue(a.open("stops.txt").isPresent());
        assertTrue(a.open("does_not_exist.txt").isEmpty());
    }
}
