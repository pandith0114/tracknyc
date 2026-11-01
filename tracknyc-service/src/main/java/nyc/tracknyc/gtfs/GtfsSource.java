package nyc.tracknyc.gtfs;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** A GTFS feed on disk: either an unpacked directory or the usual .zip. */
public final class GtfsSource {

    private final Path path;

    private GtfsSource(Path path) {
        this.path = path;
    }

    public static GtfsSource of(Path path) {
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("GTFS source not found: " + path.toAbsolutePath());
        }
        return new GtfsSource(path);
    }

    public Path path() {
        return path;
    }

    public boolean isZip() {
        return Files.isRegularFile(path);
    }

    /** Opens {@code name} (e.g. "stops.txt") if present. Caller closes the stream. */
    public Optional<InputStream> open(String name) throws IOException {
        if (!isZip()) {
            Path f = path.resolve(name);
            return Files.exists(f) ? Optional.of(Files.newInputStream(f)) : Optional.empty();
        }
        // Stream the entry rather than reading it into memory: a real stop_times.txt is well
        // over 100 MB uncompressed. The returned stream owns the ZipFile and closes it.
        ZipFile zip = new ZipFile(path.toFile());
        ZipEntry e = findEntry(zip, name);
        if (e == null) {
            zip.close();
            return Optional.empty();
        }
        return Optional.of(new FilterInputStream(zip.getInputStream(e)) {
            @Override
            public void close() throws IOException {
                try {
                    super.close();
                } finally {
                    zip.close();
                }
            }
        });
    }

    private static ZipEntry findEntry(ZipFile zip, String name) {
        ZipEntry direct = zip.getEntry(name);
        if (direct != null) {
            return direct;
        }
        // Some publishers wrap everything in a top-level folder.
        return zip.stream().filter(z -> z.getName().endsWith("/" + name)).findFirst().orElse(null);
    }

    /** SHA-256 over the feed's files, used to skip reloading an unchanged feed. */
    public String fingerprint() {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (String f : GtfsStaticReader.FILES) {
                Optional<InputStream> in = open(f);
                if (in.isPresent()) {
                    try (InputStream s = in.get()) {
                        md.update(f.getBytes());
                        md.update(s.readAllBytes());
                    }
                }
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
