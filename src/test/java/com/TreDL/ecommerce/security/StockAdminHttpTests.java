package com.TreDL.ecommerce.security;

import com.TreDL.ecommerce.repository.ProductsRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test") @Transactional
class StockAdminHttpTests {
    @Autowired MockMvc mvc;
    @Autowired ProductsRepository products;
    String input(String stock) {return "{\"nome\":\"Magazzino\",\"prezzo\":12.50"+(stock==null?"":",\"stock\":"+stock)+"}";}
    @Test void adminCanCreateSetZeroAndOmitStockWithoutResettingQuantity() throws Exception {
        String body=mvc.perform(post("/api/admin/products").with(user("admin").roles("ADMIN")).with(csrf()).contentType("application/json").content(input("5")))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.stock").value(5)).andReturn().getResponse().getContentAsString();
        long id=new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).get("id").asLong();
        mvc.perform(put("/api/admin/products/"+id).with(user("admin").roles("ADMIN")).with(csrf()).contentType("application/json").content(input(null)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.stock").value(5));
        mvc.perform(put("/api/admin/products/"+id).with(user("admin").roles("ADMIN")).with(csrf()).contentType("application/json").content(input("0")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.stock").value(0));
        assertThat(products.findById(id).orElseThrow().getStock()).isZero();
    }
    @Test void stockMustBeAnExactNonnegativeIntAndWritesRequireAdminAndCsrf() throws Exception {
        long before=products.count();
        for(String value:new String[]{"-1","1.2","2147483648","\"abc\""})
            mvc.perform(post("/api/admin/products").with(user("admin").roles("ADMIN")).with(csrf()).contentType("application/json").content(input(value)))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/products").with(user("customer").roles("USER")).with(csrf()).contentType("application/json").content(input("5"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/products").with(user("admin").roles("ADMIN")).contentType("application/json").content(input("5"))).andExpect(status().isForbidden());
        assertThat(products.count()).isEqualTo(before);
        mvc.perform(post("/api/admin/products").with(user("admin").roles("ADMIN")).with(csrf()).contentType("application/json").content(input(null)))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.stock").value(0));
    }
}
