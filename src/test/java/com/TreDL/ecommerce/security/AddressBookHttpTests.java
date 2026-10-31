package com.TreDL.ecommerce.security;
import com.TreDL.ecommerce.model.Customers;
import com.TreDL.ecommerce.repository.CustomersRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test") @Transactional
class AddressBookHttpTests {
    @Autowired MockMvc mvc;
    @Autowired CustomersRepository customers;
    @Autowired ObjectMapper mapper;
    String email;
    static final String BODY="""
        {"recipientName":" Mario Rossi ","street":" Via Roma ","houseNumber":"10","postalCode":"00100","municipality":"Roma","province":"RM"}
        """.strip();
    @BeforeEach void setup() {
        email="address-"+java.util.UUID.randomUUID()+"@example.com";
        Customers c=new Customers();c.setUsername(email);c.setEmail(email);c.setPassword("unused");c.setRole("USER");customers.saveAndFlush(c);
    }
    long create(String body) throws Exception {
        String json=mvc.perform(post("/api/addresses").with(user(email)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.recipientName").value("Mario Rossi"))
                .andExpect(jsonPath("$.country").value("IT")).andReturn().getResponse().getContentAsString();
        assertThat(json).doesNotContain("password","customer","hibernateLazyInitializer");
        return mapper.readTree(json).get("id").asLong();
    }
    @Test void authenticationCsrfAndOwnershipAreEnforced() throws Exception {
        mvc.perform(get("/api/addresses")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").exists());
        mvc.perform(post("/api/addresses").with(user(email)).contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden());
        long id=create(BODY);
        String foreign="other-"+java.util.UUID.randomUUID()+"@example.com";
        Customers c=new Customers();c.setUsername(foreign);c.setEmail(foreign);c.setPassword("unused");c.setRole("USER");customers.saveAndFlush(c);
        mvc.perform(get("/api/addresses").with(user(foreign))).andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(put("/api/addresses/"+id).with(user(foreign)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isNotFound());
        mvc.perform(delete("/api/addresses/"+id).with(user(foreign)).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(put("/api/addresses/"+id+"/default").with(user(foreign)).with(csrf())).andExpect(status().isNotFound());
    }
    @Test void defaultsArePreservedSwitchedAndPromoted() throws Exception {
        long first=create(BODY),second=create(BODY);
        mvc.perform(get("/api/addresses").with(user(email))).andExpect(jsonPath("$[0].defaultAddress").value(true)).andExpect(jsonPath("$[1].defaultAddress").value(false));
        mvc.perform(put("/api/addresses/"+first).with(user(email)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isOk()).andExpect(jsonPath("$.defaultAddress").value(true));
        mvc.perform(put("/api/addresses/"+second+"/default").with(user(email)).with(csrf())).andExpect(status().isOk()).andExpect(jsonPath("$.defaultAddress").value(true));
        mvc.perform(get("/api/addresses").with(user(email))).andExpect(jsonPath("$[0].defaultAddress").value(false));
        mvc.perform(delete("/api/addresses/"+second).with(user(email)).with(csrf())).andExpect(status().isOk());
        mvc.perform(get("/api/addresses").with(user(email))).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].defaultAddress").value(true));
    }
    @Test void malformedAndForeignAddressesAreRejected() throws Exception {
        for(String body:java.util.List.of(BODY.replace("00100","12a45"),BODY.replace("RM","rm"),BODY.replace("Mario Rossi"," "),BODY.replace("Roma","x".repeat(181)),BODY.replace("00100","１２３４５"),BODY.substring(0,BODY.length()-1)+",\"country\":\"FR\"}")) {
            mvc.perform(post("/api/addresses").with(user(email)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").exists());
        }
        mvc.perform(post("/api/addresses").with(user(email)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{" )).andExpect(status().isBadRequest());
        mvc.perform(get("/api/addresses").with(user(email))).andExpect(content().json("[]"));
    }
}
