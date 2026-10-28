package com.TreDL.ecommerce.release;
import javax.sql.DataSource;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
@Configuration(proxyBeanMethods=false)
@Profile(ReleaseDatabase.PROFILE)
public class ReleaseConfiguration {
    @Bean public DataSource dataSource(Environment environment) {
        DataSource source=ReleaseDatabase.target(environment::getProperty).runtimeSource();
        try(java.sql.Connection connection=source.getConnection()) {
            ReleaseDatabase.requireV1(connection);
        } catch(Exception failure) { throw new IllegalStateException("Verified V1 schema and runtime privileges required before startup"); }
        return source;
    }
}
