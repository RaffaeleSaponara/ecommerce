package com.TreDL.ecommerce.catalog;

import com.TreDL.ecommerce.model.Products;
import com.TreDL.ecommerce.repository.ProductsRepository;
import org.junit.jupiter.api.BeforeEach;
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

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test") @Transactional
class CatalogContractTests {
    @Autowired MockMvc mvc;
    @Autowired ProductsRepository products;
    @BeforeEach void fixtures() {
        products.deleteAll();
        product("Alpha Jig", "Hardbaits", "10.00");
        product("ALPHA Soft", "Softbaits", "20.00");
        product("100%_Jig", "Hardbaits", "0.00");
        product("Maximum", null, "99999999999999999.99");
    }
    void product(String name, String category, String price) {
        Products p = new Products(); p.setNome(name); p.setCategoria(category);
        p.setPrezzo(new BigDecimal(price)); products.saveAndFlush(p);
    }
    @Test void allAndBlank() throws Exception {
        mvc.perform(get("/prodotti/all")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(4));
        mvc.perform(get("/prodotti/all").param("q", " ").param("category", " ").param("minPrice", "").param("maxPrice", " "))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(4));
    }
    @Test void nameAndLiteralWildcards() throws Exception {
        mvc.perform(get("/prodotti/all").param("q", " aLpHa ")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/prodotti/all").param("q", "%_")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].nome").value("100%_Jig"));
    }
    @Test void categoryAndCombination() throws Exception {
        mvc.perform(get("/prodotti/all").param("category", " hardBAITS ")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/prodotti/all").param("q", "alpha").param("category", "Hardbaits").param("minPrice", "10.00").param("maxPrice", "10"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].nome").value("Alpha Jig"));
    }
    @Test void emptyResults() throws Exception {
        mvc.perform(get("/prodotti/all").param("q", "missing")).andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(get("/prodotti/all").param("category", "missing")).andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(get("/prodotti/all").param("q", "alpha").param("category", "Hardbaits").param("minPrice", "10.01"))
            .andExpect(status().isOk()).andExpect(content().json("[]"));
    }
    @Test void inclusivePriceLimits() throws Exception {
        mvc.perform(get("/prodotti/all").param("maxPrice", "0")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/prodotti/all").param("minPrice", "20")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/prodotti/all").param("minPrice", "99999999999999999.99").param("maxPrice", "99999999999999999.99"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
    }
    @ParameterizedTest @ValueSource(strings={"-1", "abc", "NaN", "Infinity", "1e2", "1,00", "0.001", "1.000", "100000000000000000", "99999999999999999.999"})
    void invalidPrices(String value) throws Exception {
        for (String key : new String[]{"minPrice", "maxPrice"})
            mvc.perform(get("/prodotti/all").param(key, value)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").isNotEmpty());
    }
    @Test void invertedRange() throws Exception {
        mvc.perform(get("/prodotti/all").param("minPrice", "20").param("maxPrice", "10"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").isNotEmpty());
    }
    @Test void pageHasAccessibleControls() throws Exception {
        mvc.perform(get("/prodotti")).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"catalog-filters\"")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"catalog-status\"")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("role=\"search\"")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"filter-q\"")))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("id=\"filter-category\""))))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("id=\"filter-min\""))))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("id=\"filter-max\""))));
    }
}
