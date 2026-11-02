package com.TreDL.ecommerce.service;
import org.springframework.boot.ApplicationArguments; import org.springframework.boot.ApplicationRunner; import org.springframework.stereotype.Component;
@Component public class ShippingInitializer implements ApplicationRunner {
 private final ShippingService shipping; public ShippingInitializer(ShippingService shipping){this.shipping=shipping;}
 public void run(ApplicationArguments args){shipping.initialize();}
}