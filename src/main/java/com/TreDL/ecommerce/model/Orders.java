package com.TreDL.ecommerce.model;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
@Entity
@Table(name = "orders", uniqueConstraints = @UniqueConstraint(name = "uq_orders_customer_key", columnNames = {"id_customer", "idempotency_key"}))
public class Orders {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    /** UTC by application convention. */
    @Column(nullable = false) private LocalDateTime date;
    @Column(nullable = false, precision = 19, scale = 2) private BigDecimal total;
    @Column(nullable = false, columnDefinition = "CHAR(3)") private String currency = "EUR";
    @Column(name = "ship_status", nullable = false) private String shipment;
    @Column(name = "idempotency_key", nullable = false, length = 128, columnDefinition = "VARBINARY(128)") private byte[] idempotencyKey;
    @Column(name = "request_hash", nullable = false, length = 32, columnDefinition = "BINARY(32)") private byte[] requestHash;
    @ManyToOne(optional = false) @JoinColumn(name = "id_customer", nullable = false) private Customers customer;
    @OneToMany(mappedBy = "order", cascade = CascadeType.PERSIST) @OrderBy("lineNumber ASC")
    private List<OrderItem> items = new ArrayList<>();
    public Long getId() { return id; }
    public LocalDateTime getDate() { return date; }
    public void setDate(LocalDateTime date) { this.date = Objects.requireNonNull(date); }
    public BigDecimal getTotal() { return total; }
    public void setTotal(BigDecimal total) { this.total = Money.validate(total); }
    public String getCurrency() { return currency; }
    public String getShipment() { return shipment; }
    public void setShipment(String shipment) { this.shipment = Objects.requireNonNull(shipment); }
    public Customers getCustomer() { return customer; }
    public void setCustomer(Customers customer) { this.customer = Objects.requireNonNull(customer); }
    public byte[] getIdempotencyKey() { return idempotencyKey == null ? null : idempotencyKey.clone(); }
    public void setIdempotencyKey(byte[] key) {
        if (key == null || key.length < 1 || key.length > 128) throw new IllegalArgumentException("Key must contain 1..128 bytes");
        idempotencyKey = key.clone();
    }
    public byte[] getRequestHash() { return requestHash == null ? null : requestHash.clone(); }
    public void setRequestHash(byte[] hash) {
        if (hash == null || hash.length != 32) throw new IllegalArgumentException("SHA-256 must contain 32 bytes");
        requestHash = hash.clone();
    }
    public List<OrderItem> getItems() { return items.stream().sorted(Comparator.comparingInt(OrderItem::getLineNumber)).toList(); }
    public void addItem(OrderItem item) {
        Objects.requireNonNull(item);
        if (item.getOrder() != this) throw new IllegalArgumentException("Item belongs to another order");
        if (items.stream().anyMatch(i -> i.getLineNumber() == item.getLineNumber())) throw new IllegalArgumentException("Duplicate line number");
        items.add(item);
    }
    @PrePersist @PreUpdate private void validate() {
        total = Money.validate(total);
        Objects.requireNonNull(customer); Objects.requireNonNull(date); Objects.requireNonNull(shipment);
        if (!"EUR".equals(currency)) throw new IllegalArgumentException("Only EUR supported");
        setIdempotencyKey(idempotencyKey); setRequestHash(requestHash);
    }
}
