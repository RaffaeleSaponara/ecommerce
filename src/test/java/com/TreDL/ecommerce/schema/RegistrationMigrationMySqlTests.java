package com.TreDL.ecommerce.schema;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import java.sql.*;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class RegistrationMigrationMySqlTests {
    @Test void duplicateHistoricalEmailsBlockV7WithoutRewritingAccounts() throws Exception {
        String owner=UUID.randomUUID().toString();
        try(var mysql=new MySQLContainer<>("mysql:8.4.3").withDatabaseName("registration_migration")
                .withUsername("registration_test").withPassword(UUID.randomUUID().toString())
                .withReuse(false).withLabel("com.3dl.registration-test",owner)
                .withTmpFs(Map.of("/var/lib/mysql","rw,size=1g"))
                .withCreateContainerCmdModifier(c->c.getHostConfig().withPortBindings(new com.github.dockerjava.api.model.PortBinding(
                    com.github.dockerjava.api.model.Ports.Binding.bindIpAndPort("127.0.0.1",0),com.github.dockerjava.api.model.ExposedPort.tcp(3306))))) {
            mysql.start();
            assertThat(mysql.getContainerInfo().getConfig().getLabels()).containsEntry("com.3dl.registration-test",owner);
            assertThat(mysql.getContainerInfo().getHostConfig().getTmpFs()).containsEntry("/var/lib/mysql","rw,size=1g");
            String url="jdbc:mysql://127.0.0.1:"+mysql.getMappedPort(3306)+"/registration_migration";
            Flyway.configure().dataSource(url,mysql.getUsername(),mysql.getPassword()).locations("classpath:db/checkout-v2").target("6").load().migrate();
            try(Connection db=DriverManager.getConnection(url,mysql.getUsername(),mysql.getPassword());Statement sql=db.createStatement()) {
                sql.executeUpdate("INSERT INTO customers(id,username,email,password,role) VALUES(1,'First',' User@Example.com ','first-hash','USER'),(2,'Second','user@example.COM','second-hash','ADMIN')");
                var upgrade=Flyway.configure().dataSource(url,mysql.getUsername(),mysql.getPassword()).locations("classpath:db/checkout-v2").load();
                assertThatThrownBy(upgrade::migrate).isInstanceOf(org.flywaydb.core.api.FlywayException.class);
                try(var rows=sql.executeQuery("SELECT id,email,password,role FROM customers ORDER BY id")) {
                    assertThat(rows.next()).isTrue();assertThat(rows.getLong(1)).isEqualTo(1);
                    assertThat(rows.getString(2)).isEqualTo(" User@Example.com ");assertThat(rows.getString(3)).isEqualTo("first-hash");assertThat(rows.getString(4)).isEqualTo("USER");
                    assertThat(rows.next()).isTrue();assertThat(rows.getLong(1)).isEqualTo(2);
                    assertThat(rows.getString(2)).isEqualTo("user@example.COM");assertThat(rows.getString(3)).isEqualTo("second-hash");assertThat(rows.getString(4)).isEqualTo("ADMIN");
                    assertThat(rows.next()).isFalse();
                }
            }
        }
    }
}
