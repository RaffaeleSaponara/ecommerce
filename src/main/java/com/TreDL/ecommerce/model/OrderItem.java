package com.TreDL.ecommerce.model;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.Objects;
@Entity
@Table(name = "order_items", uniqueConstraints = @UniqueConstraint(name = "uq_order_items_order_line", columnNames = {"id_order", "line_number"}))
public class OrderItem {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(optional = false) @JoinColumn(name = "id_order", nullable = false, updatable = false) private Orders order;
    @Column(name = "line_number", nullable = false, updatable = false) private int lineNumber;
    @ManyToOne @JoinColumn(name = "id_product", updatable = false) private Products product;
    @Column(name = "product_name", nullable = false, updatable = false) private String productName;
    @Column(name = "unit_price", nullable = false, precision = 19, scale = 2, updatable = false) private BigDecimal unitPrice;
    @Column(nullable = false, updatable = false) private int quantity;
    protected OrderItem() {}
    public OrderItem(Orders order, int lineNumber, Products product, int quantity) {
        this.order = Objects.requireNonNull(order); this.product = Objects.requireNonNull(product);
        this.lineNumber = lineNumber; this.productName = Objects.requireNonNull(product.getNome());
        this.unitPrice = Money.validate(product.getPrezzo()); this.quantity = quantity; validate();
    }
    @PrePersist @PreUpdate private void validate() {
        if (lineNumber <= 0) throw new IllegalArgumentException("Line number must be positive");
        Objects.requireNonNull(order); Objects.requireNonNull(productName);
        unitPrice = Money.validate(unitPrice); Money.subtotal(unitPrice, quantity);
    }
    public Long getId() { return id; }
    public Orders getOrder() { return order; }
    public int getLineNumber() { return lineNumber; }
    public Products getProduct() { return product; }
    public String getProductName() { return productName; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public int getQuantity() { return quantity; }
    public BigDecimal getSubtotal() { return Money.subtotal(unitPrice, quantity); }
}
