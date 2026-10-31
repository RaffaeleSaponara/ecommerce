package com.TreDL.ecommerce.service;
import com.TreDL.ecommerce.dto.*;
import com.TreDL.ecommerce.model.*;
import com.TreDL.ecommerce.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.*;
import static org.springframework.http.HttpStatus.*;
@Service
@Transactional(isolation=Isolation.READ_COMMITTED,rollbackFor=Exception.class)
public class AddressBookService {
    private final CustomersRepository customers;
    private final CustomerAddressesRepository addresses;
    public AddressBookService(CustomersRepository customers,CustomerAddressesRepository addresses) { this.customers=customers;this.addresses=addresses; }
    @Transactional(readOnly=true)
    public List<AddressDTO> list(String email) { return addresses.findByCustomerIdOrderByIdAsc(customer(email).getId()).stream().map(AddressDTO::from).toList(); }
    public AddressDTO create(String email,AddressRequest request) {
        var customer=lock(email); var value=validate(request); var all=addresses.findByCustomerIdOrderByIdAsc(customer.getId());
        var address=new CustomerAddress(); address.setCustomer(customer); address.setAddress(value);
        address.setDefaultAddress(all.isEmpty() || request.defaultAddress());
        if(address.isDefaultAddress()) all.forEach(a->a.setDefaultAddress(false));
        return AddressDTO.from(addresses.saveAndFlush(address));
    }
    public AddressDTO update(String email,Long id,AddressRequest request) {
        var customer=lock(email); var address=owned(customer,id); var value=validate(request);
        if(request.defaultAddress()) selectDefault(customer,address);
        address.setAddress(value); return AddressDTO.from(address);
    }
    public AddressDTO makeDefault(String email,Long id) { var customer=lock(email); var address=owned(customer,id);selectDefault(customer,address);return AddressDTO.from(address); }
    public void delete(String email,Long id) {
        var customer=lock(email); var address=owned(customer,id); addresses.delete(address);
        if(address.isDefaultAddress()) addresses.findByCustomerIdOrderByIdAsc(customer.getId()).stream().filter(a->!a.getId().equals(id)).findFirst().ifPresent(a->a.setDefaultAddress(true));
    }
    private void selectDefault(Customers customer,CustomerAddress selected) { addresses.findByCustomerIdOrderByIdAsc(customer.getId()).forEach(a->a.setDefaultAddress(a.getId().equals(selected.getId()))); }
    private AddressSnapshot validate(AddressRequest request) { if(request==null) throw new CheckoutException(BAD_REQUEST,"Indirizzo obbligatorio");return request.validated(); }
    private CustomerAddress owned(Customers customer,Long id) { return addresses.findByIdAndCustomerId(id,customer.getId()).orElseThrow(()->new CheckoutException(NOT_FOUND,"Indirizzo non trovato")); }
    private Customers customer(String email) { if(email==null) throw new CheckoutException(UNAUTHORIZED,"Autenticazione richiesta");return customers.findByEmail(email).orElseThrow(()->new CheckoutException(UNAUTHORIZED,"Cliente non trovato")); }
    private Customers lock(String email) { return customers.findByIdForUpdate(customer(email).getId()).orElseThrow(()->new CheckoutException(UNAUTHORIZED,"Cliente non trovato")); }
}
