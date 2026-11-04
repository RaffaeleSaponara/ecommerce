package com.TreDL.ecommerce.service;
import com.TreDL.ecommerce.model.*;
import jakarta.persistence.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;
import java.util.*;
import java.math.BigDecimal;
@Service
public class ProductInformationService {
 @PersistenceContext private EntityManager em;
 public record Data(String modelCode,String identification,String materials,String dimensions,String manufacturerName,String manufacturerAddress,String manufacturerEmail,String warnings,String instructions,BigDecimal weightGrams,boolean verified) {}
 public record Editor(Data data,List<String> missing) {}
 @Transactional(readOnly=true) public Editor editor(long id){product(id,false);var x=em.find(ProductInformation.class,id);return new Editor(data(x),missing(data(x)));}
 @Transactional(readOnly=true) public Data publicData(long id){var p=product(id,false);if(!p.isActive())throw new ResponseStatusException(NOT_FOUND);var x=em.find(ProductInformation.class,id);return x!=null && x.verified && missing(data(x)).isEmpty()?data(x):null;}
 @Transactional public Editor save(long id,Data input){
  var p=product(id,true);if(input==null)throw new IllegalArgumentException("Scheda richiesta");
  Data d=new Data(text(input.modelCode(),80),text(input.identification(),255),text(input.materials(),1000),text(input.dimensions(),255),text(input.manufacturerName(),150),text(input.manufacturerAddress(),500),text(input.manufacturerEmail(),254),text(input.warnings(),2000),text(input.instructions(),2000),input.weightGrams(),input.verified());
  if(d.weightGrams()!=null && (d.weightGrams().signum()<=0 || d.weightGrams().scale()>3 || d.weightGrams().compareTo(new BigDecimal("999999.999"))>0))throw new IllegalArgumentException("Peso non valido: grammi positivi, massimo tre decimali");
  if(d.manufacturerEmail()!=null && !d.manufacturerEmail().matches("[^\\s@]+@[^\\s@]+[.][^\\s@]+"))throw new IllegalArgumentException("Email fabbricante non valida");
  var missing=missing(d);if(d.verified() && !missing.isEmpty())throw new IllegalArgumentException("Completa i campi prima di confermare la scheda: "+String.join(", ",missing));
  var x=em.find(ProductInformation.class,id);if(x==null){x=new ProductInformation();x.product=p;}
  x.modelCode=d.modelCode(); x.identification=d.identification(); x.materials=d.materials(); x.dimensions=d.dimensions(); x.manufacturerName=d.manufacturerName(); x.manufacturerAddress=d.manufacturerAddress(); x.manufacturerEmail=d.manufacturerEmail(); x.warnings=d.warnings(); x.instructions=d.instructions(); x.weightGrams=d.weightGrams();x.verified=d.verified();
  if(x.productId==null)em.persist(x);em.flush();return new Editor(data(x),missing);
 }
 private Products product(long id,boolean lock){var p=lock?em.find(Products.class,id,LockModeType.PESSIMISTIC_WRITE):em.find(Products.class,id);if(p==null)throw new ResponseStatusException(NOT_FOUND,"Prodotto non trovato");return p;}
 private Data data(ProductInformation x){return x==null?new Data(null,null,null,null,null,null,null,null,null,null,false):new Data(x.modelCode,x.identification,x.materials,x.dimensions,x.manufacturerName,x.manufacturerAddress,x.manufacturerEmail,x.warnings,x.instructions,x.weightGrams,x.verified);}
 private List<String> missing(Data d){var m=new ArrayList<String>();if(unknown(d.modelCode()))m.add("modelCode"); if(unknown(d.identification()))m.add("identification"); if(unknown(d.materials()))m.add("materials"); if(unknown(d.dimensions()))m.add("dimensions"); if(unknown(d.manufacturerName()))m.add("manufacturerName"); if(unknown(d.manufacturerAddress()))m.add("manufacturerAddress"); if(unknown(d.manufacturerEmail()))m.add("manufacturerEmail"); if(unknown(d.warnings()))m.add("warnings"); if(unknown(d.instructions()))m.add("instructions");if(d.weightGrams()==null)m.add("weightGrams");return List.copyOf(m);}
 private boolean unknown(String s){return s==null || s.toUpperCase(Locale.ROOT).contains("[DA COMPLETARE") || s.toUpperCase(Locale.ROOT).contains("[DA VERIFICARE");}
 private String text(String s,int max){if(s==null||s.isBlank())return null;s=s.strip();if(s.length()>max||s.codePoints().anyMatch(c->Character.isISOControl(c)&&c!='\n'&&c!='\r'&&c!='\t'))throw new IllegalArgumentException("Testo troppo lungo o non valido");return s;}
}
