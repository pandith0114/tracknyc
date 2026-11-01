package nyc.tracknyc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/** TrackNYC — live map of NYC regional trains. */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class TrackNycApplication {

    public static void main(String[] args) {
        SpringApplication.run(TrackNycApplication.class, args);
    }
}
