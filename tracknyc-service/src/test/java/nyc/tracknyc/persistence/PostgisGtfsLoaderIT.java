package nyc.tracknyc.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZoneId;
import javax.sql.DataSource;
import nyc.tracknyc.Fixtures;
import nyc.tracknyc.gtfs.GtfsSource;
import nyc.tracknyc.network.SystemNetwork;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Loads the synthetic subway GTFS into a real PostGIS (Testcontainers) and reads it back. */
@Tag("docker")
@Testcontainers(disabledWithoutDocker = true)
class PostgisGtfsLoaderIT {

    @Container
    static final PostgreSQLContainer<?> PG = PostgisContainers.create();

    static JdbcTemplate jdbc;
    static PostgisGtfsLoader loader;
    static PostgisNetworkRepository repository;

    @BeforeAll
    static void setUp() {
        DataSource ds = PostgisContainers.migrated(PG);
        jdbc = new JdbcTemplate(ds);
        loader = new PostgisGtfsLoader(new JdbcTemplate(ds), new TransactionTemplate(new DataSourceTransactionManager(ds)));
        repository = new PostgisNetworkRepository(new JdbcTemplate(ds));
        loader.loadIfChanged("subway", GtfsSource.of(Fixtures.dir().resolve("gtfs/subway")), "America/New_York");
    }

    @Test
    void loadsAllTablesWithGeometry() {
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM gtfs_route WHERE system='subway'", Integer.class));
        assertEquals(201, jdbc.queryForObject("SELECT count(*) FROM gtfs_stop WHERE system='subway'", Integer.class));
        assertEquals(6, jdbc.queryForObject("SELECT count(*) FROM gtfs_shape WHERE system='subway'", Integer.class));
        assertTrue(jdbc.queryForObject("SELECT count(*) FROM gtfs_stop_time", Integer.class) > 20_000);
        // Bedford Av is roughly 1.0-1.2 km from Lorimer St... in the synthetic data; check PostGIS distance works.
        Double meters = jdbc.queryForObject("""
                SELECT ST_Distance(a.geom::geography, b.geom::geography) FROM gtfs_stop a, gtfs_stop b
                WHERE a.stop_id = 'L08' AND b.stop_id = 'L06' AND a.system = 'subway' AND b.system = 'subway'""",
                Double.class);
        assertTrue(meters > 2000 && meters < 3000, "Bedford Av -> 1 Av ~2.4 km: " + meters);
        Integer near = jdbc.queryForObject("SELECT count(*) FROM gtfs_stop WHERE location_type = 1 AND "
                + nyc.live.core.geo.PostgisSql.withinMeters("geom"), Integer.class, -73.9567, 40.7173, 800.0);
        assertTrue(near >= 2, "stations within 800 m of Bedford Av");
    }

    @Test
    void reloadIsSkippedWhenUnchanged() {
        assertFalse(loader.loadIfChanged("subway", GtfsSource.of(Fixtures.dir().resolve("gtfs/subway")), "America/New_York"));
    }

    @Test
    void readBackMatchesFileModel() {
        SystemNetwork fromDb = repository.load("subway", ZoneId.of("America/New_York")).orElseThrow();
        SystemNetwork fromFile = Fixtures.network("subway");
        assertEquals(fromFile.trips().size(), fromDb.trips().size());
        assertEquals(fromFile.stations().size(), fromDb.stations().size());
        var t = fromDb.trip("SYN26-L-Daily-00_048000_L..N01R").orElseThrow();
        assertEquals(fromFile.trip(t.id()).orElseThrow().lastArrival(), t.lastArrival());
        assertEquals("America/New_York", fromDb.zone().getId());
    }
}
