package com.TreDL.ecommerce.controller;
import com.TreDL.ecommerce.service.WithdrawalService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;
@Controller
public class WithdrawalController {
 private final WithdrawalService service;
 public WithdrawalController(WithdrawalService service){this.service=service;}
 @GetMapping("/recesso") public String form(Principal principal,Model model){model.addAttribute("choices",service.choices(principal.getName()));model.addAttribute("receiptEmail",principal.getName());return "withdrawal";}
 @PostMapping("/recesso") public String submit(Principal principal,@RequestParam long orderId,@RequestParam String name,@RequestParam(defaultValue="false") boolean confirmed){
  return "redirect:/recesso/"+service.submit(principal.getName(),orderId,name,confirmed).id();
 }
 @GetMapping("/recesso/{id}") public String receipt(Principal principal,@PathVariable String id,Model model){model.addAttribute("receipt",service.receipt(principal.getName(),id));return "withdrawal";}
 @GetMapping(value="/recesso/{id}/ricevuta",produces="text/plain;charset=UTF-8") @ResponseBody
 public org.springframework.http.ResponseEntity<String> download(Principal principal,@PathVariable String id){
  var receipt=service.receipt(principal.getName(),id);
  return org.springframework.http.ResponseEntity.ok().header("Content-Disposition","attachment; filename=ricevuta-recesso.txt").header("Cache-Control","no-store").body(receipt.declaration());
 }
 @GetMapping("/admin/recessi") public String admin(Model model){model.addAttribute("requests",service.admin());return "withdrawal";}
 @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
 public String invalid(IllegalArgumentException e,Model model){model.addAttribute("error",e.getMessage());return "withdrawal";}
}
