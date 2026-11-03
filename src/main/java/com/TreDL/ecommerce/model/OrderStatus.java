package com.TreDL.ecommerce.model;

public enum OrderStatus {
    IN_ATTESA_PAGAMENTO, CONFERMATO, IN_PREPARAZIONE, SPEDITO, CONSEGNATO, ANNULLATO;

    public static OrderStatus current(String value) {
        // Existing checkout orders keep their original persisted value until updated.
        if ("In elaborazione".equals(value)) return CONFERMATO;
        try { return valueOf(value); }
        catch (RuntimeException error) { throw new IllegalArgumentException("Stato ordine non riconosciuto"); }
    }

    public boolean allows(OrderStatus next) {
        return switch (this) {
            case IN_ATTESA_PAGAMENTO -> next == CONFERMATO || next == ANNULLATO;
            case CONFERMATO -> next == IN_PREPARAZIONE || next == ANNULLATO;
            case IN_PREPARAZIONE -> next == SPEDITO || next == ANNULLATO;
            case SPEDITO -> next == CONSEGNATO;
            default -> false;
        };
    }
}
