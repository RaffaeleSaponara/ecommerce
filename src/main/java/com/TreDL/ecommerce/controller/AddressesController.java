package com.TreDL.ecommerce.controller;
import com.TreDL.ecommerce.dto.*;
import com.TreDL.ecommerce.service.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;
import java.util.*;
@RestController @RequestMapping("/api/addresses")
public class AddressesController {
    private final AddressBookService addresses;
    public AddressesController(AddressBookService addresses) { this.addresses=addresses; }
    @GetMapping public List<AddressDTO> list(Principal principal) { return addresses.list(principal.getName()); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED) public AddressDTO create(Principal principal,@RequestBody AddressRequest request) { return addresses.create(principal.getName(),request); }
    @PutMapping("/{id}") public AddressDTO update(Principal principal,@PathVariable Long id,@RequestBody AddressRequest request) { return addresses.update(principal.getName(),id,request); }
    @PutMapping("/{id}/default") public AddressDTO makeDefault(Principal principal,@PathVariable Long id) { return addresses.makeDefault(principal.getName(),id); }
    @DeleteMapping("/{id}") public Map<String,String> delete(Principal principal,@PathVariable Long id) { addresses.delete(principal.getName(),id);return Map.of("message","Indirizzo eliminato"); }
    @ExceptionHandler(CheckoutException.class) public ResponseEntity<Map<String,String>> error(CheckoutException error) { return ResponseEntity.status(error.getStatus()).body(Map.of("message",error.getMessage())); }
    @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String,String>> invalid(Exception error) { return ResponseEntity.badRequest().body(Map.of("message","Richiesta indirizzo non valida")); }
}
