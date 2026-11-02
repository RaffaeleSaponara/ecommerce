package com.TreDL.ecommerce.schema;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import java.sql.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class EmailVerificationMigrationMySqlTests {
    @Test void v9PreservesHistoricalUsersAndAdminButDefaultsFutureInsertsToUnverified() throws Exception {
        String owner=UUID.randomUUID().toString();
        try(var mysql=new MySQLContainer<>("mysql:8.4.3").withDatabaseName("verification_migration").withUsername("verification_test").withPassword(UUID.randomUUID().toString())
                .withReuse(false).withLabel("com.3dl.verification-test",owner).withTmpFs(Map.of("/var/lib/mysql","rw,size=1g"))
                .withCreateContainerCmdModifier(c->c.getHostConfig().withPortBindings(new com.github.dockerjava.api.model.PortBinding(com.github.dockerjava.api.model.Ports.Binding.bindIpAndPort("127.0.0.1",0),com.github.dockerjava.api.model.ExposedPort.tcp(3306))))) {
            mysql.start();assertThat(mysql.getContainerInfo().getConfig().getLabels()).containsEntry("com.3dl.verification-test",owner);
            String url="jdbc:mysql://127.0.0.1:"+mysql.getMappedPort(3306)+"/verification_migration";
            Flyway.configure().dataSource(url,mysql.getUsername(),mysql.getPassword()).locations("classpath:db/checkout-v2").target("8").load().migrate();
            try(Connection db=DriverManager.getConnection(url,mysql.getUsername(),mysql.getPassword());Statement sql=db.createStatement()) {
                sql.executeUpdate("INSERT INTO customers(id,username,email,password,role) VALUES(1,'Legacy','legacy@example.com','original-user-hash','USER'),(2,'Admin','admin@example.com','original-admin-hash','ADMIN')");
                var migration=Flyway.configure().dataSource(url,mysql.getUsername(),mysql.getPassword()).locations("classpath:db/checkout-v2").load();assertThat(migration.migrate().migrationsExecuted).isEqualTo(1);
                try(var rows=sql.executeQuery("SELECT id,password,role,email_verified FROM customers ORDER BY id")) {
                    assertThat(rows.next()).isTrue();assertThat(rows.getLong(1)).isEqualTo(1);assertThat(rows.getString(2)).isEqualTo("original-user-hash");assertThat(rows.getString(3)).isEqualTo("USER");assertThat(rows.getBoolean(4)).isTrue();
                    assertThat(rows.next()).isTrue();assertThat(rows.getLong(1)).isEqualTo(2);assertThat(rows.getString(2)).isEqualTo("original-admin-hash");assertThat(rows.getString(3)).isEqualTo("ADMIN");assertThat(rows.getBoolean(4)).isTrue();
                }
                sql.executeUpdate("INSERT INTO customers(username,email,password,role) VALUES('New','new@example.com','new-hash','USER')");
                try(var rows=sql.executeQuery("SELECT email_verified FROM customers WHERE username='New'")){assertThat(rows.next()).isTrue();assertThat(rows.getBoolean(1)).isFalse();}
                assertThat(migration.migrate().migrationsExecuted).isZero();
            }
        }
    }
}
