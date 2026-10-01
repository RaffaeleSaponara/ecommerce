package com.TreDL.ecommerce;

import com.TreDL.ecommerce.model.Products;
import com.TreDL.ecommerce.repository.ProductsRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
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
                12.90,
                "/images/b1.jpg"
        );
        Products chatterDark = createProduct(
                "Chatterbait Dark Flash",
                "Chatterbait compatta con vibrazione marcata e palette scura per acqua velata.",
                "Hardbaits",
                14.50,
                "/images/b4.jpg"
        );
        Products softShad = createProduct(
                "Soft Shad Pearl",
                "Softbait shad con profilo realistico e coda ad alta mobilita per recuperi lenti.",
                "Softbaits",
                7.90,
                "/images/b8.jpg"
        );

        productsRepository.saveAll(List.of(jigNatural, chatterDark, softShad));
    }

    private Products createProduct(String nome, String descrizione, String categoria, double prezzo, String image) {
        Products product = new Products();
        product.setNome(nome);
        product.setDescrizione(descrizione);
        product.setCategoria(categoria);
        product.setPrezzo(prezzo);
        product.setImage(image);
        return product;
    }
}
