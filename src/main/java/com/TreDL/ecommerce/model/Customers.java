package com.TreDL.ecommerce.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.Hibernate;

import java.util.List;
import java.util.Objects;

@Entity
@AllArgsConstructor
@NoArgsConstructor
@Table(name = "customers", uniqueConstraints = @UniqueConstraint(name="uq_customers_username",columnNames="username"))
public class Customers {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @OneToMany(mappedBy = "customer")
    @ToString.Exclude
    private List<Orders> orders;
    @OneToOne(mappedBy = "customer", cascade = {CascadeType.PERSIST, CascadeType.MERGE})
    private Cart cart;
    @Column(name = "username", nullable = false)
    private String username;
    @Column(name = "password", nullable = false)
    private String password;
    @Column(name = "email")
    private String email;
    @Column(name = "role", nullable = false)
    private String role;
    @Column(name="email_verified",nullable=false) private boolean emailVerified=false;
    public boolean isEmailVerified(){return emailVerified;}
    public void setEmailVerified(boolean value){emailVerified=value;}
    // Preserve the existing constructor API; verification is always explicit for new objects.
    public Customers(Long id,List<Orders> orders,Cart cart,String username,String password,String email,String role) {
        this.id=id;this.orders=orders;this.cart=cart;this.username=username;this.password=password;this.email=email;this.role=role;
    }


    public List<Orders> getOrders() {
        return orders;
    }

    public void setOrders(List<Orders> orders) {
        this.orders = orders;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public Cart getCart() {
        return cart;
    }

    public void setCart(Cart cart) {
        this.cart = cart;
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }
}
