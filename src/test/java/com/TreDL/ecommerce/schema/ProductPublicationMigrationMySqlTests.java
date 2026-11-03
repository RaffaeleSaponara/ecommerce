package com.TreDL.ecommerce.schema;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import java.sql.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class ProductPublicationMigrationMySqlTests {
 @Test void v10PreservesProductsAndDefaultsNewProductsToActive() throws Exception {
  String owner=UUID.randomUUID().toString();
  try(var mysql=new MySQLContainer<>("mysql:8.4.3").withDatabaseName("publication_migration").withUsername("publication_test").withPassword(UUID.randomUUID().toString())
    .withReuse(false).withLabel("com.3dl.publication-test",owner).withTmpFs(Map.of("/var/lib/mysql","rw,size=1g"))
    .withCreateContainerCmdModifier(c->c.getHostConfig().withPortBindings(new com.github.dockerjava.api.model.PortBinding(com.github.dockerjava.api.model.Ports.Binding.bindIpAndPort("127.0.0.1",0),com.github.dockerjava.api.model.ExposedPort.tcp(3306))))) {
   mysql.start();assertThat(mysql.getContainerInfo().getConfig().getLabels()).containsEntry("com.3dl.publication-test",owner);
   String url="jdbc:mysql://127.0.0.1:"+mysql.getMappedPort(3306)+"/publication_migration";
   Flyway.configure().dataSource(url,mysql.getUsername(),mysql.getPassword()).locations("classpath:db/checkout-v2").target("9").load().migrate();
   try(Connection db=DriverManager.getConnection(url,mysql.getUsername(),mysql.getPassword());Statement sql=db.createStatement()) {
    sql.executeUpdate("INSERT INTO products(id,nome,prezzo,stock) VALUES(1,'Legacy',12.50,0)");
    var migration=Flyway.configure().dataSource(url,mysql.getUsername(),mysql.getPassword()).locations("classpath:db/checkout-v2").target("10").load();
    assertThat(migration.migrate().migrationsExecuted).isEqualTo(1);
    try(var rows=sql.executeQuery("SELECT nome,prezzo,stock,active FROM products WHERE id=1")) {
     assertThat(rows.next()).isTrue();assertThat(rows.getString(1)).isEqualTo("Legacy");
     assertThat(rows.getBigDecimal(2)).isEqualByComparingTo("12.50");assertThat(rows.getInt(3)).isZero();assertThat(rows.getBoolean(4)).isTrue();
    }
    sql.executeUpdate("INSERT INTO products(id,nome,prezzo,stock) VALUES(2,'New',1.00,5)");
    try(var rows=sql.executeQuery("SELECT active FROM products WHERE id=2")){assertThat(rows.next()).isTrue();assertThat(rows.getBoolean(1)).isTrue();}
    assertThat(migration.migrate().migrationsExecuted).isZero();
   }
  }
 }
}
