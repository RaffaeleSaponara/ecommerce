package com.TreDL.ecommerce.schema;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class AddressMigrationTests {
    @Test void v3PreservesOldOrdersAndLeavesMissingAddressSnapshotsNullable() throws Exception {
        String url="jdbc:h2:mem:addresses_"+UUID.randomUUID().toString().replace("-","")+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url,"sa","").locations("classpath:db/checkout-v2").target("2").cleanDisabled(true).load().migrate();
        try(Connection db=DriverManager.getConnection(url,"sa","");Statement s=db.createStatement()) {
            s.executeUpdate("INSERT INTO customers(id,username,password,role) VALUES (1,'legacy','fixture','USER')");
            try(PreparedStatement insert=db.prepareStatement("INSERT INTO orders(id,id_customer,date,total,currency,ship_status,idempotency_key,request_hash) VALUES(1,1,CURRENT_TIMESTAMP,12.50,'EUR','Spedito',?,?)")) {
                insert.setBytes(1,new byte[]{1});insert.setBytes(2,new byte[32]);insert.executeUpdate();
            }
            Flyway upgraded=Flyway.configure().dataSource(url,"sa","").locations("classpath:db/checkout-v2").target("3").cleanDisabled(true).load();
            assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(1);upgraded.validate();
            assertThat(upgraded.info().current().getVersion().toString()).isEqualTo("3");
            try(ResultSet r=s.executeQuery("SELECT total,ship_status,request_hash,shipping_recipient_name,billing_recipient_name FROM orders WHERE id=1")) {
                assertThat(r.next()).isTrue();assertThat(r.getBigDecimal(1)).isEqualByComparingTo("12.50");assertThat(r.getString(2)).isEqualTo("Spedito");
                assertThat(r.getBytes(3)).containsExactly(new byte[32]);assertThat(r.getString(4)).isNull();assertThat(r.getString(5)).isNull();
            }
            assertThat(upgraded.migrate().migrationsExecuted).isZero();
        }
    }
}
