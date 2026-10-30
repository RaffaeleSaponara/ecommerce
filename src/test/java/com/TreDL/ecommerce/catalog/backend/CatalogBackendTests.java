package com.TreDL.ecommerce.catalog.backend;

import com.TreDL.ecommerce.model.Products;
import com.TreDL.ecommerce.repository.ProductsRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class CatalogBackendTests {
    @Autowired MockMvc mvc;
    @Autowired ProductsRepository products;

    @Test
    void searchTreatsBackslashesAndSqlTextLiterally() throws Exception {
        products.deleteAll();
        product("Jig\\100%_x ' OR 1=1", "Gear%_");
        product("Jig100ZZx", "GearZZ");
        for (String query : new String[]{"\\", "%_", "' OR 1=1"}) {
            mvc.perform(get("/prodotti/all").param("q", query))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].categoria").value("Gear%_"));
        }
        mvc.perform(get("/prodotti/all").param("category", " gEaR%_ "))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void trimmedPricesAndLeadingZerosRetainExactInclusiveBounds() throws Exception {
        products.deleteAll();
        product("Exact price", null);
        mvc.perform(get("/prodotti/all").param("minPrice", " 00010.5 ").param("maxPrice", " 10.50 "))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].nome").value("Exact price"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"+1", "-0", ".5", "1.", "1 0", "1\n0", "\u0661", "0.000"})
    void rejectsOtherNonDecimalSyntax(String value) throws Exception {
        for (String parameter : new String[]{"minPrice", "maxPrice"}) {
            mvc.perform(get("/prodotti/all").param(parameter, value))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").isNotEmpty());
        }
    }

    private void product(String name, String category) {
        Products product = new Products();
        product.setNome(name);
        product.setCategoria(category);
        product.setPrezzo(new BigDecimal("10.50"));
        products.saveAndFlush(product);
    }
}
