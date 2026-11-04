package com.TreDL.ecommerce.service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
/** Shared placeholders, not a declaration of a verified seller identity. */
@Component
public record SellerInfo(
 @Value("${app.seller.name:[DA COMPLETARE: nome/denominazione del venditore e fabbricante]}") String name,
 @Value("${app.seller.address:[DA COMPLETARE: indirizzo postale]}") String address,
 @Value("${app.seller.email:[DA COMPLETARE: email di contatto]}") String email,
 @Value("${app.seller.privacy-contact:[DA COMPLETARE: contatto privacy]}") String privacyContact,
 @Value("${app.seller.returns-address:[DA COMPLETARE: indirizzo di reso]}") String returnsAddress) {}
