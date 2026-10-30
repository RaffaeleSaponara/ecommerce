package com.TreDL.ecommerce.security;

import com.TreDL.ecommerce.model.Products;
import com.TreDL.ecommerce.repository.*;
import com.TreDL.ecommerce.service.ProductImageStorage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProductImagesHttpTests {
    @TempDir static Path directory;
    @DynamicPropertySource static void storage(DynamicPropertyRegistry properties) {
        properties.add("product.images.directory", () -> directory.toString());
    }
    @Autowired MockMvc mvc;
    @Autowired ProductsRepository products;
    @Autowired ProductImagesRepository images;
    @Autowired ProductImageStorage storage;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager transactions;

    private Products product() {
        Products product = new Products();
        product.setNome("Image test"); product.setPrezzo(new BigDecimal("12.50")); product.setImage("/images/b1.jpg");
        return products.saveAndFlush(product);
    }
    private MockMultipartFile file() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB), "png", bytes);
        return new MockMultipartFile("file", "product.png", "image/png", bytes.toByteArray());
    }
    private String path(long id) { return "/api/admin/products/" + id + "/images"; }
    private com.fasterxml.jackson.databind.JsonNode upload(long id) throws Exception {
        return json.readTree(mvc.perform(multipart(path(id)).file(file()).with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    @Test void anonymousAndCustomerCannotManageImagesAndCsrfRemainsRequired() throws Exception {
        long id = product().getId();
        mvc.perform(get(path(id))).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").exists());
        mvc.perform(multipart(path(id)).file(file()).with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(get(path(id)).with(user("customer").roles("USER"))).andExpect(status().isForbidden());
        mvc.perform(multipart(path(id)).file(file()).with(user("customer").roles("USER")).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(multipart(path(id)).file(file()).with(user("admin").roles("ADMIN"))).andExpect(status().isForbidden());
        assertThat(images.findByProductIdOrderByIdAsc(id)).isEmpty();
    }

    @Test void publicDetailAndAdminImageLifecycleKeepCatalogPrimarySynchronized() throws Exception {
        long id = product().getId();
        mvc.perform(get("/api/products/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.product.image").value("/images/b1.jpg")).andExpect(jsonPath("$.images").isEmpty());
        var first = upload(id); var second = upload(id);
        assertThat(first.get("primary").asBoolean()).isTrue();
        assertThat(second.get("primary").asBoolean()).isFalse();
        String firstUrl = first.get("url").asText(), secondUrl = second.get("url").asText();
        mvc.perform(get(firstUrl)).andExpect(status().isOk()).andExpect(content().contentType("image/png"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        mvc.perform(put(path(id) + "/" + second.get("id").asLong() + "/primary").with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.primary").value(true));
        assertThat(products.findById(id).orElseThrow().getImage()).isEqualTo(secondUrl);
        mvc.perform(delete(path(id) + "/" + second.get("id").asLong()).with(user("admin").roles("ADMIN")).with(csrf())).andExpect(status().isOk());
        assertThat(products.findById(id).orElseThrow().getImage()).isEqualTo(firstUrl);
        mvc.perform(get(secondUrl)).andExpect(status().isNotFound());
        mvc.perform(delete(path(id) + "/" + first.get("id").asLong()).with(user("admin").roles("ADMIN")).with(csrf())).andExpect(status().isOk());
        assertThat(products.findById(id).orElseThrow().getImage()).isEqualTo("/images/product-placeholder.svg");
        mvc.perform(get(firstUrl)).andExpect(status().isNotFound());
    }

    @Test void ownershipLimitAndInvalidFilesHaveNoEffects() throws Exception {
        long id = product().getId(), other = product().getId();
        var first = upload(id);
        mvc.perform(put(path(other) + "/" + first.get("id").asLong() + "/primary").with(user("admin").roles("ADMIN")).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(delete(path(other) + "/" + first.get("id").asLong()).with(user("admin").roles("ADMIN")).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(multipart(path(id)).file(new MockMultipartFile("file", "bad.png", "image/png", new byte[]{1,2,3}))
                .with(user("admin").roles("ADMIN")).with(csrf())).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").exists());
        for (int i = 1; i < 10; i++) upload(id);
        mvc.perform(multipart(path(id)).file(file()).with(user("admin").roles("ADMIN")).with(csrf())).andExpect(status().isBadRequest());
        assertThat(images.findByProductIdOrderByIdAsc(id)).hasSize(10);
    }

    @Test void missingProductsAndUnsafeLegacyImagesAreHandled() throws Exception {
        mvc.perform(get("/api/products/9223372036854775807")).andExpect(status().isNotFound());
        Products product = product(); product.setImage("javascript:alert(1)"); products.saveAndFlush(product);
        mvc.perform(get("/api/products/" + product.getId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.product.image").value("/images/product-placeholder.svg"));
    }

    @Test void actualDetailTemplateEscapesTextInitializesCsrfAndHandlesFallbacks() throws Exception {
        Products product = product();
        product.setNome("<script>alert(1)</script>");
        product.setDescrizione("<img src=x onerror=alert(1)>");
        products.saveAndFlush(product);
        String html = mvc.perform(get("/prodotti/" + product.getId())).andExpect(status().isOk())
                .andExpect(view().name("prodotto")).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("&lt;script&gt;", "&lt;img", "12.50", "/images/b1.jpg", "_csrf");
        assertThat(html).doesNotContain("<script>alert(1)</script>", "<img src=x onerror=alert(1)>");
        product.setImage("https://unsafe.example/a.png"); products.saveAndFlush(product);
        mvc.perform(get("/prodotti/" + product.getId())).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/images/product-placeholder.svg")));
        mvc.perform(get("/prodotti/9223372036854775807")).andExpect(status().isNotFound());
    }

    @Test void productFormCannotOverwriteManagedPrimaryAndDeletionRollbackKeepsFile() throws Exception {
        long id = product().getId();
        var image = upload(id);
        mvc.perform(put("/api/admin/products/" + id).with(user("admin").roles("ADMIN")).with(csrf())
                .contentType("application/json")
                .content("{\"nome\":\"Edited\",\"prezzo\":20,\"image\":\"/images/b1.jpg\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.image").value(image.get("url").asText()));
        new TransactionTemplate(transactions).execute(status -> {
            try {
                mvc.perform(delete(path(id) + "/" + image.get("id").asLong()).with(user("admin").roles("ADMIN")).with(csrf()))
                        .andExpect(status().isOk());
            } catch (Exception error) { throw new RuntimeException(error); }
            status.setRollbackOnly(); return null;
        });
        mvc.perform(get(image.get("url").asText())).andExpect(status().isOk());
        assertThat(images.findByProductIdOrderByIdAsc(id)).hasSize(1);
    }

    @Test void rolledBackUploadRemovesFileAndReference() throws Exception {
        long id = product().getId();
        final String[] url = {null};
        new TransactionTemplate(transactions).execute(status -> {
            try { url[0] = upload(id).get("url").asText(); }
            catch (Exception error) { throw new RuntimeException(error); }
            status.setRollbackOnly(); return null;
        });
        assertThat(images.findByProductIdOrderByIdAsc(id)).isEmpty();
        assertThat(directory.resolve(url[0].substring("/media/products/".length()))).doesNotExist();
        assertThat(products.findById(id).orElseThrow().getImage()).isEqualTo("/images/b1.jpg");
    }
}
