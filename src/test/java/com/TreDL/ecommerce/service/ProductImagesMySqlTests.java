package com.TreDL.ecommerce.service;

import com.TreDL.ecommerce.model.Products;
import com.TreDL.ecommerce.repository.ProductsRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
@ContextConfiguration(initializers=MySqlCheckoutTestDatabase.Initializer.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class ProductImagesMySqlTests {
    @TempDir static Path temporary;
    @DynamicPropertySource static void storage(DynamicPropertyRegistry registry) {
        registry.add("product.images.directory", () -> temporary.resolve("images").toString());
    }
    @Autowired MockMvc mvc;
    @Autowired ProductsRepository products;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired MySqlCheckoutTestDatabase owned;
    long productId;
    @BeforeEach void fixture() {
        jdbc.update("DELETE FROM product_images");
        products.deleteAll();
        Products p=new Products();p.setNome("Jig immagini");p.setDescrizione("Descrizione originale");
        p.setPrezzo(new BigDecimal("12.50"));p.setImage("/images/b1.jpg");
        productId=products.saveAndFlush(p).getId();
    }
    byte[] png() throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(3,2,BufferedImage.TYPE_INT_RGB),"png",out);return out.toByteArray();
    }
    com.fasterxml.jackson.databind.JsonNode upload() throws Exception {
        String json=mvc.perform(multipart("/api/admin/products/"+productId+"/images")
                .file(new MockMultipartFile("file","../../client-name.png","image/png",png()))
                .with(user("admin").roles("ADMIN")).with(csrf()))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(json);
    }
    String path(long imageId) {return "/api/admin/products/"+productId+"/images/"+imageId;}
    @Test void mysqlRoundTripPreservesProductAndOnlyPersistsFileReferences() throws Exception {
        assertThat(jdbc.queryForObject("SELECT DATABASE()",String.class)).isEqualTo(owned.databaseName());
        assertThat(jdbc.queryForObject("SELECT @@server_uuid",String.class)).isEqualTo(owned.expectedServerUuid());
        var first=upload();var second=upload();
        assertThat(first.get("primary").asBoolean()).isTrue();
        assertThat(second.get("primary").asBoolean()).isFalse();
        String url=first.get("url").asText();
        assertThat(url).matches("/media/products/[a-f0-9-]{36}\\.png");
        mvc.perform(get(url)).andExpect(status().isOk()).andExpect(content().contentType("image/png"))
            .andExpect(header().string("X-Content-Type-Options","nosniff"));
        mvc.perform(put(path(second.get("id").asLong())+"/primary").with(user("admin").roles("ADMIN")).with(csrf()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.primary").value(true));
        assertThat(products.findById(productId).orElseThrow().getImage()).isEqualTo(second.get("url").asText());
        mvc.perform(delete(path(second.get("id").asLong())).with(user("admin").roles("ADMIN")).with(csrf()))
            .andExpect(status().isOk());
        assertThat(products.findById(productId).orElseThrow().getImage()).isEqualTo(url);
        mvc.perform(get(second.get("url").asText())).andExpect(status().isNotFound());
        mvc.perform(delete(path(first.get("id").asLong())).with(user("admin").roles("ADMIN")).with(csrf()))
            .andExpect(status().isOk());
        mvc.perform(get("/api/products/"+productId)).andExpect(status().isOk())
            .andExpect(jsonPath("$.product.image").value("/images/product-placeholder.svg"))
            .andExpect(jsonPath("$.images.length()").value(0));
        assertThat(products.findById(productId).orElseThrow().getNome()).isEqualTo("Jig immagini");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='2' AND success=1",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT data_type FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='product_images'",String.class))
            .noneMatch(type->type.toLowerCase().contains("blob"));
    }
    @Test void concurrentUploadsKeepOnePrincipalWithRealMysqlRowLock() throws Exception {
        ExecutorService workers=Executors.newFixedThreadPool(2);
        try {
            var a=workers.submit(this::upload);var b=workers.submit(this::upload);
            a.get(20,TimeUnit.SECONDS);b.get(20,TimeUnit.SECONDS);
            var images=mapper.readTree(mvc.perform(get("/api/admin/products/"+productId+"/images").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            assertThat(images.size()).isEqualTo(2);
            long principals=java.util.stream.StreamSupport.stream(images.spliterator(),false).filter(i->i.get("primary").asBoolean()).count();
            assertThat(principals).isEqualTo(1);
        } finally {workers.shutdownNow();assertThat(workers.awaitTermination(10,TimeUnit.SECONDS)).isTrue();}
    }
}
