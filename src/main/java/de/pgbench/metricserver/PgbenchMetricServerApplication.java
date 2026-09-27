package de.pgbench.metricserver;

import de.pgbench.metricserver.config.PgbenchProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(PgbenchProperties.class)
public class PgbenchMetricServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(PgbenchMetricServerApplication.class, args);
    }
}
