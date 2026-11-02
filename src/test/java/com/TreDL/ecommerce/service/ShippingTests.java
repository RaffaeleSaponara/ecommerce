package com.TreDL.ecommerce.service;
import com.TreDL.ecommerce.dto.*; import org.junit.jupiter.api.*; import org.springframework.beans.factory.annotation.Autowired; import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc; import org.springframework.test.web.servlet.MockMvc; import java.math.BigDecimal; import java.util.Base64;
import static org.assertj.core.api.Assertions.*; import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*; import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*; import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@AutoConfigureMockMvc class ShippingTests extends StockCheckoutContract {
 @Autowired ShippingService shipping; @Autowired MockMvc mvc; @Autowired QuoteSigner signer;
 @BeforeEach void tariffs(){shipping.update(new ShippingService.Settings(new BigDecimal("5.90"),new BigDecimal("79.00")));}
 void price(String value){jdbc.update("UPDATE products SET prezzo=? WHERE id=?",new BigDecimal(value),product.getId());}
 @Test void belowExactAboveThreshold(){
  carts.addProductToCart(alice,product);
  for(String value:new String[]{"78.99","79.00","79.01"}){
   price(value);var q=checkout.quote(alice.getEmail());
   BigDecimal cost=value.equals("78.99")?new BigDecimal("5.90"):new BigDecimal("0.00");
   assertThat(q.subtotal()).isEqualByComparingTo(value);assertThat(q.shippingCost()).isEqualTo(cost);assertThat(q.total()).isEqualTo(new BigDecimal(value).add(cost));
  }
 }
 @Test void changedTariffRequiresNewConfirmationButReplayKeepsSnapshot(){
  carts.addProductToCart(alice,product);var original=checkout.quote(alice.getEmail());
  shipping.update(new ShippingService.Settings(new BigDecimal("7.00"),new BigDecimal("79.00")));
  conflict(()->checkout.checkout(alice.getEmail(),"shipping",new CheckoutRequest(original.quoteToken(),true,aliceAddress,null)));
  assertThat(available()).isEqualTo(5);assertThat(orders.count()).isZero();
  var q=checkout.quote(alice.getEmail());var request=new CheckoutRequest(q.quoteToken(),true,aliceAddress,null);
  var order=checkout.checkout(alice.getEmail(),"shipping",request);
  assertThat(order.subtotal()).isEqualByComparingTo("12.50");assertThat(order.shippingCost()).isEqualByComparingTo("7.00");assertThat(order.total()).isEqualByComparingTo("19.50");
  shipping.update(new ShippingService.Settings(new BigDecimal("99.00"),new BigDecimal("0.00")));
  assertThat(checkout.checkout(alice.getEmail(),"shipping",request)).isEqualTo(order);assertThat(available()).isEqualTo(4);
  var saved=orders.findById(order.id()).orElseThrow();assertThat(saved.getSubtotal()).isEqualByComparingTo("12.50");assertThat(saved.getShippingCost()).isEqualByComparingTo("7.00");assertThat(saved.getTotal()).isEqualByComparingTo("19.50");
 }
 @Test void exactThresholdCheckoutPersistsFreeShipping(){
  price("79.00");carts.addProductToCart(alice,product);var order=checkout.checkout(alice.getEmail(),"exact",request(alice));
  var saved=orders.findById(order.id()).orElseThrow();assertThat(saved.getSubtotal()).isEqualByComparingTo("79.00");assertThat(saved.getShippingCost()).isEqualByComparingTo("0.00");assertThat(saved.getTotal()).isEqualByComparingTo("79.00");assertThat(available()).isEqualTo(4);
 }
 @Test void signedShippingAndFinalTotalCannotBeTampered(){
  carts.addProductToCart(alice,product);var q=checkout.quote(alice.getEmail());String[] parts=q.quoteToken().split("\\.");
  String json=new String(Base64.getUrlDecoder().decode(parts[0]),java.nio.charset.StandardCharsets.UTF_8);
  assertThat(json).contains("shippingCost","subtotal");
  String altered=Base64.getUrlEncoder().withoutPadding().encodeToString(json.replace("5.90","0.00").getBytes(java.nio.charset.StandardCharsets.UTF_8))+"."+parts[1];
  assertThatThrownBy(()->signer.verify(altered,alice.getId())).isInstanceOf(CheckoutException.class);
  String alteredTotal=Base64.getUrlEncoder().withoutPadding().encodeToString(json.replace("18.40","0.00").getBytes(java.nio.charset.StandardCharsets.UTF_8))+"."+parts[1];
  assertThat(alteredTotal).isNotEqualTo(q.quoteToken());assertThatThrownBy(()->signer.verify(alteredTotal,alice.getId())).isInstanceOf(CheckoutException.class);
 }
 @Test void adminTariffsTrackingAndOwnership() throws Exception {
  String settings="/api/admin/shipping-settings";
  mvc.perform(put(settings).with(user(alice.getEmail())).with(csrf()).contentType("application/json").content("{\"cost\":0,\"freeThreshold\":0}")).andExpect(status().isForbidden());
  mvc.perform(put(settings).with(user("admin").roles("ADMIN")).with(csrf()).contentType("application/json").content("{\"cost\":-1,\"freeThreshold\":79}")).andExpect(status().isBadRequest());
  mvc.perform(put(settings).with(user("admin").roles("ADMIN")).with(csrf()).contentType("application/json").content("{\"cost\":4.90,\"freeThreshold\":60}")).andExpect(status().isOk()).andExpect(jsonPath("$.cost").value(4.90));
  carts.addProductToCart(alice,product);long id=checkout.checkout(alice.getEmail(),"track",request(alice)).id();
  String url="/api/admin/orders/"+id+"/tracking",body="{\"carrier\":\"Corriere test\",\"trackingCode\":\"TRACK-123\"}";
  mvc.perform(put(url).with(user(alice.getEmail())).with(csrf()).contentType("application/json").content(body)).andExpect(status().isForbidden());
  mvc.perform(put(url).with(user("admin").roles("ADMIN")).contentType("application/json").content(body)).andExpect(status().isForbidden());
  mvc.perform(put(url).with(user("admin").roles("ADMIN")).with(csrf()).contentType("application/json").content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.trackingCode").value("TRACK-123"));
  mvc.perform(get("/user").with(user(alice.getEmail()))).andExpect(content().string(org.hamcrest.Matchers.containsString("TRACK-123")));
  mvc.perform(get("/user").with(user(bob.getEmail()))).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("TRACK-123"))));
 }
}
