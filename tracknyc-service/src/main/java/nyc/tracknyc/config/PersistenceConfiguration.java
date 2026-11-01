package nyc.tracknyc.config;

import javax.sql.DataSource;
import nyc.tracknyc.persistence.PostgisGtfsLoader;
import nyc.tracknyc.persistence.PostgisNetworkRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** PostGIS-backed static data (the default). Disabled with {@code tracknyc.static-data.source=file}. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "tracknyc.static-data", name = "source", havingValue = "postgis", matchIfMissing = true)
public class PersistenceConfiguration {

    @Bean
    PostgisGtfsLoader postgisGtfsLoader(DataSource dataSource, PlatformTransactionManager txManager) {
        return new PostgisGtfsLoader(new JdbcTemplate(dataSource), new TransactionTemplate(txManager));
    }

    @Bean
    PostgisNetworkRepository postgisNetworkRepository(DataSource dataSource) {
        return new PostgisNetworkRepository(new JdbcTemplate(dataSource));
    }
}
