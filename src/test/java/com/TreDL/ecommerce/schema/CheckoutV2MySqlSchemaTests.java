package com.TreDL.ecommerce.schema;

import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.ExposedPort;
import java.sql.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class CheckoutV2MySqlSchemaTests extends CheckoutV2SchemaContract {
    private static final String RUN=UUID.randomUUID().toString().replace("-","");
    private static String serverUuid;
    @Container private static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4.3")
        .withDatabaseName("checkout_v2_schema_"+RUN).withUsername("checkout_test").withPassword(UUID.randomUUID().toString())
        .withReuse(false).withLabel("com.3dl.checkout.schema-test",RUN).withTmpFs(Map.of("/var/lib/mysql","rw,size=1g"))
        .withCreateContainerCmdModifier(c->c.getHostConfig().withPortBindings(new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1",0),ExposedPort.tcp(3306))));
    private static void owned() {
        var info=MYSQL.getContainerInfo();
        assertThat(info.getConfig().getImage()).isEqualTo("mysql:8.4.3");
        assertThat(info.getConfig().getLabels().get("com.3dl.checkout.schema-test")).isEqualTo(RUN);
        assertThat(info.getHostConfig().getTmpFs()).containsEntry("/var/lib/mysql","rw,size=1g");
        assertThat(info.getMounts()).allMatch(m->"tmpfs".equals(m.getRawValues().get("Type")));
        assertThat(info.getNetworkSettings().getPorts().getBindings().get(ExposedPort.tcp(3306))[0].getHostIp()).isEqualTo("127.0.0.1");
    }
    @BeforeAll static void identifyOwnedServer() throws Exception {
        owned();var identity=MYSQL.execInContainer("cat","/var/lib/mysql/auto.cnf");
        assertThat(identity.getExitCode()).isZero();
        serverUuid=identity.getStdout().lines().filter(l->l.startsWith("server-uuid=")).findFirst().orElseThrow().substring(12).trim();
    }
    @Override protected String url(){owned();return MYSQL.getJdbcUrl();}
    @Override protected String username(){return MYSQL.getUsername();}
    @Override protected String password(){return MYSQL.getPassword();}
    @Override protected void assertDatabaseIdentity(Connection connection) throws SQLException {
        owned();
        assertThat(connection.getMetaData().getURL().split("\\?",2)[0]).isEqualTo(MYSQL.getJdbcUrl().split("\\?",2)[0]);
        try(Statement s=connection.createStatement();ResultSet r=s.executeQuery("SELECT DATABASE(),@@server_uuid,VERSION()")) {
            assertThat(r.next()).isTrue();assertThat(r.getString(1)).isEqualTo(MYSQL.getDatabaseName());
            assertThat(r.getString(2)).isEqualTo(serverUuid);assertThat(r.getString(3)).isEqualTo("8.4.3");
        }
    }
}
