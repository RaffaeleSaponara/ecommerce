package com.TreDL.ecommerce.service;

import com.TreDL.ecommerce.dto.*;
import com.TreDL.ecommerce.model.*;
import com.TreDL.ecommerce.repository.*;
import jakarta.persistence.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.math.BigDecimal;
import java.nio.*;
import java.nio.charset.*;
import java.security.MessageDigest;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static org.springframework.http.HttpStatus.*;

@Service
@Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
public class CheckoutService {
    private final CustomersRepository customers;
    private final CartRepository carts;
    private final OrdersRepository orders;
    private final QuoteSigner signer;
    @PersistenceContext private EntityManager entityManager;

    public CheckoutService(CustomersRepository customers, CartRepository carts,
                           OrdersRepository orders, QuoteSigner signer) {
        this.customers = customers;
        this.carts = carts;
        this.orders = orders;
        this.signer = signer;
    }

    public QuoteDTO quote(String authenticatedEmail) {
        Customers customer = lockCustomer(authenticatedEmail);
        Cart cart = nonEmptyCart(customer);
        List<QuoteLineDTO> lines = currentLines(cart);
        BigDecimal total = total(lines);
        long expiry = signer.expiresAt();
        var payload = new QuoteSigner.Payload(1, customer.getId(), expiry, "EUR", total, lines);
        return new QuoteDTO(signer.sign(payload), expiry, "EUR", total, lines);
    }

    public OrderDTO checkout(String authenticatedEmail, String clientKey, CheckoutRequest request) {
        byte[] key = keyBytes(clientKey);
        if (request == null || !request.confirmPrices())
            throw new CheckoutException(BAD_REQUEST, "Explicit price confirmation is required");
        byte[] hash = QuoteSigner.requestHash(request.quoteToken());
        Customers customer = lockCustomer(authenticatedEmail);
        // All checkout and cart writers serialize on this row. Under READ_COMMITTED,
        // the waiter sees the preceding commit rather than a pre-lock snapshot.
        Optional<Orders> previous = orders.findByCustomerIdAndIdempotencyKey(customer.getId(), key);
        if (previous.isPresent()) {
            if (!MessageDigest.isEqual(previous.get().getRequestHash(), hash))
                throw new CheckoutException(CONFLICT, "Idempotency key already used for another request");
            return OrderDTO.convertToDTO(previous.get());
        }
        QuoteSigner.Payload confirmed = signer.verify(request.quoteToken(), customer.getId());
        Cart cart = nonEmptyCart(customer);
        List<QuoteLineDTO> current = currentLines(cart);
        BigDecimal total = total(current);
        // Catalog locks may have blocked past the quote's validity window.
        signer.requireUnexpired(confirmed);
        if (!current.equals(confirmed.items()) || !"EUR".equals(confirmed.currency()) || !total.equals(confirmed.total()))
            throw new CheckoutException(CONFLICT, "Cart or prices changed; request and confirm a new quote");

        Orders order = new Orders();
        order.setCustomer(customer);
        order.setDate(LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS));
        order.setShipment("In elaborazione");
        order.setIdempotencyKey(key);
        order.setRequestHash(hash);
        order.setTotal(total);
        int lineNumber = 1;
        for (QuoteLineDTO line : current) {
            Products product = entityManager.find(Products.class, line.productId());
            order.addItem(new OrderItem(order, lineNumber++, product, line.quantity()));
        }
        orders.saveAndFlush(order); // Includes all rows; any failure rolls everything back.
        cart.getProducts().clear();
        carts.saveAndFlush(cart);
        return OrderDTO.convertToDTO(order);
    }

    private Customers lockCustomer(String email) {
        if (email == null) throw new CheckoutException(UNAUTHORIZED, "Authentication required");
        Customers customer = customers.findByEmail(email)
                .orElseThrow(() -> new CheckoutException(UNAUTHORIZED, "Customer not found"));
        return customers.findByIdForUpdate(customer.getId())
                .orElseThrow(() -> new CheckoutException(UNAUTHORIZED, "Customer not found"));
    }

    private Cart nonEmptyCart(Customers customer) {
        Cart cart = carts.findByCustomerId(customer.getId())
                .orElseThrow(() -> new CheckoutException(CONFLICT, "Cart is empty"));
        if (cart.getProducts() == null || cart.getProducts().isEmpty())
            throw new CheckoutException(CONFLICT, "Cart is empty");
        return cart;
    }

    private List<QuoteLineDTO> currentLines(Cart cart) {
        Map<Long, Integer> quantities = new TreeMap<>();
        for (Products occurrence : cart.getProducts()) quantities.merge(occurrence.getId(), 1, Math::addExact);
        List<QuoteLineDTO> lines = new ArrayList<>();
        for (var entry : quantities.entrySet()) {
            Products product = entityManager.find(Products.class, entry.getKey(), LockModeType.PESSIMISTIC_READ);
            if (product == null) throw new CheckoutException(CONFLICT, "Product no longer available");
            // Refresh even if cart loading put the product in the first-level cache.
            // Hold catalog locks through commit so a price cannot change after verification.
            entityManager.refresh(product, LockModeType.PESSIMISTIC_READ);
            BigDecimal price = Money.validate(product.getPrezzo());
            lines.add(new QuoteLineDTO(product.getId(), product.getNome(), price, entry.getValue(),
                    Money.subtotal(price, entry.getValue())));
        }
        return List.copyOf(lines);
    }

    private static BigDecimal total(List<QuoteLineDTO> lines) {
        BigDecimal total = new BigDecimal("0.00");
        for (QuoteLineDTO line : lines) total = Money.validate(total.add(line.subtotal()));
        return total;
    }

    public static byte[] keyBytes(String key) {
        if (key == null) throw new CheckoutException(BAD_REQUEST, "Idempotency-Key is required");
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(key));
            if (encoded.remaining() < 1 || encoded.remaining() > 128)
                throw new CheckoutException(BAD_REQUEST, "Idempotency-Key must contain 1..128 UTF-8 bytes");
            byte[] bytes = new byte[encoded.remaining()]; encoded.get(bytes); return bytes;
        } catch (CharacterCodingException e) { throw new CheckoutException(BAD_REQUEST, "Invalid UTF-8 key"); }
    }
}
