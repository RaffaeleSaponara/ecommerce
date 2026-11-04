package com.TreDL.ecommerce.controller;
import com.TreDL.ecommerce.service.ProductInformationService;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/admin/products/{id}/information")
public class ProductInformationController {
 private final ProductInformationService service;
 public ProductInformationController(ProductInformationService service){this.service=service;}
 @GetMapping public ProductInformationService.Editor get(@PathVariable long id){return service.editor(id);}
 @PutMapping public ProductInformationService.Editor save(@PathVariable long id,@RequestBody ProductInformationService.Data input){return service.save(id,input);}
 @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
 public java.util.Map<String,String> invalid(IllegalArgumentException e){return java.util.Map.of("message",e.getMessage());}
}
