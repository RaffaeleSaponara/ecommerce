package com.TreDL.ecommerce.service;

import com.TreDL.ecommerce.model.Cart;
import com.TreDL.ecommerce.model.Orders;
import com.TreDL.ecommerce.model.Products;
import com.TreDL.ecommerce.repository.CustomersRepository;
import com.TreDL.ecommerce.repository.OrdersRepository;
import org.hibernate.query.Order;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class OrdersService {
    @Autowired
    private OrdersRepository ordersRepository;
    public Orders createOrder(Cart cart) {

        double total=0;

        for (Products p: cart.getProducts()) {
            total+=p.getPrezzo();
        }

        Orders order = new Orders();
        order.setCustomer(cart.getCustomer());
        order.setDate(LocalDateTime.now());
        order.setShipment("In elaborazione");
        order.setTotal(total);
        order.setProducts(new ArrayList<>(cart.getProducts()));

        return ordersRepository.save(order);
    }
}
