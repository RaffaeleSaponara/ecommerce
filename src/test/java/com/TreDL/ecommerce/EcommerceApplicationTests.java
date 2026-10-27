package com.TreDL.ecommerce;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class EcommerceApplicationTests extends JwtTestSupport {

	@Autowired
	private DataSource dataSource;

	@Test
	void contextLoadsWithInMemoryH2() throws Exception {
		try (Connection connection = dataSource.getConnection()) {
			assertThat(connection.getMetaData().getURL()).startsWith("jdbc:h2:mem:");
			assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("H2");
		}
	}

}
