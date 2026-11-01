package nyc.tracknyc.persistence;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Shared PostGIS container setup for integration tests. */
public final class PostgisContainers {

    public static final DockerImageName POSTGIS = DockerImageName.parse("postgis/postgis:16-3.4")
            .asCompatibleSubstituteFor("postgres");

    private PostgisContainers() {
    }

    public static PostgreSQLContainer<?> create() {
        return new PostgreSQLContainer<>(POSTGIS).withDatabaseName("tracknyc").withUsername("tracknyc")
                .withPassword("tracknyc");
    }

    public static DataSource migrated(PostgreSQLContainer<?> pg) {
        DriverManagerDataSource ds = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        return ds;
    }
}
