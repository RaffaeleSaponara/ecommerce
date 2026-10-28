package com.TreDL.ecommerce;

import com.TreDL.ecommerce.model.Products;
import com.TreDL.ecommerce.repository.ProductsRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.util.List;
import java.math.BigDecimal;

@Component
@Profile("checkout-browser")
@ConditionalOnProperty(name = "app.data-initializer.enabled", havingValue = "true", matchIfMissing = false)
public class DataInitializer implements CommandLineRunner {
    private final ProductsRepository productsRepository;

    public DataInitializer(ProductsRepository productsRepository) {
        this.productsRepository = productsRepository;
    }

    @Override
    public void run(String... args) {
        if (productsRepository.count() > 0) {
            return;
        }

        Products jigNatural = createProduct(
                "Jig Natural Craw",
                "Jig artigianale con skirt siliconico e finitura naturale, pensato per bass in cover e fondali misti.",
                "Hardbaits",
                new BigDecimal("12.90"),
                "/images/b1.jpg"
        );
        Products chatterDark = createProduct(
                "Chatterbait Dark Flash",
                "Chatterbait compatta con vibrazione marcata e palette scura per acqua velata.",
                "Hardbaits",
                new BigDecimal("14.50"),
                "/images/b4.jpg"
        );
        Products softShad = createProduct(
                "Soft Shad Pearl",
                "Softbait shad con profilo realistico e coda ad alta mobilita per recuperi lenti.",
                "Softbaits",
                new BigDecimal("7.90"),
                "/images/b8.jpg"
        );

        productsRepository.saveAll(List.of(jigNatural, chatterDark, softShad));
    }

    private Products createProduct(String nome, String descrizione, String categoria, BigDecimal prezzo, String image) {
        Products product = new Products();
        product.setNome(nome);
        product.setDescrizione(descrizione);
        product.setCategoria(categoria);
        product.setPrezzo(prezzo);
        product.setImage(image);
        return product;
    }
}
