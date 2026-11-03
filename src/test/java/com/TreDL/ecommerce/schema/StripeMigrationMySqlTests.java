package com.TreDL.ecommerce.schema;

import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class StripeMigrationMySqlTests extends StripeMigrationTests {
    private static final String OWNER=UUID.randomUUID().toString();
    @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4.3")
        .withDatabaseName("stripe_migration").withUsername("stripe_test").withPassword(UUID.randomUUID().toString())
        .withReuse(false).withLabel("com.3dl.stripe-test",OWNER).withTmpFs(Map.of("/var/lib/mysql","rw,size=1g"))
        .withCreateContainerCmdModifier(c->c.getHostConfig().withPortBindings(new com.github.dockerjava.api.model.PortBinding(
            com.github.dockerjava.api.model.Ports.Binding.bindIpAndPort("127.0.0.1",0),com.github.dockerjava.api.model.ExposedPort.tcp(3306))));
    @Override protected String url(){
        assertThat(MYSQL.getContainerInfo().getConfig().getLabels()).containsEntry("com.3dl.stripe-test",OWNER);
        return "jdbc:mysql://127.0.0.1:"+MYSQL.getMappedPort(3306)+"/stripe_migration";
    }
    @Override protected String username(){return MYSQL.getUsername();}
    @Override protected String password(){return MYSQL.getPassword();}
}
