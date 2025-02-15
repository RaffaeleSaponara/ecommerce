package com.TreDL.ecommerce.service;

import com.TreDL.ecommerce.model.Customers;
import com.TreDL.ecommerce.repository.CustomersRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class CustomersService {
    @Autowired
    private CustomersRepository customersRepository;
    public Customers getByUsername(String name) {
        return customersRepository.findByUsername(name).isPresent() ? customersRepository.findByUsername(name).get() : new Customers();
    }
}
