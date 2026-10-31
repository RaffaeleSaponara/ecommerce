package com.TreDL.ecommerce.model;
import jakarta.persistence.*;
/** Immutable address value captured when the order is placed. */
@Embeddable
public class AddressSnapshot {
    @Column(name="recipient_name", length=150) private String recipientName;
    @Column(name="street", length=180) private String street;
    @Column(name="house_number", length=20) private String houseNumber;
    @Column(name="postal_code", length=5) private String postalCode;
    @Column(name="municipality", length=100) private String municipality;
    @Column(name="province", length=2) private String province;
    @Column(name="country", length=2) private String country;
    @Column(name="delivery_notes", length=500) private String deliveryNotes;
    protected AddressSnapshot() {}
    public AddressSnapshot(String recipientName, String street, String houseNumber, String postalCode, String municipality, String province, String country, String deliveryNotes) {
        this.recipientName=recipientName;
        this.street=street;
        this.houseNumber=houseNumber;
        this.postalCode=postalCode;
        this.municipality=municipality;
        this.province=province;
        this.country=country;
        this.deliveryNotes=deliveryNotes;
    }
    public String getRecipientName() { return recipientName; }
    public String getStreet() { return street; }
    public String getHouseNumber() { return houseNumber; }
    public String getPostalCode() { return postalCode; }
    public String getMunicipality() { return municipality; }
    public String getProvince() { return province; }
    public String getCountry() { return country; }
    public String getDeliveryNotes() { return deliveryNotes; }
    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof AddressSnapshot a)) return false;
        return java.util.Objects.equals(recipientName, a.recipientName) && java.util.Objects.equals(street, a.street) && java.util.Objects.equals(houseNumber, a.houseNumber) && java.util.Objects.equals(postalCode, a.postalCode) && java.util.Objects.equals(municipality, a.municipality) && java.util.Objects.equals(province, a.province) && java.util.Objects.equals(country, a.country) && java.util.Objects.equals(deliveryNotes, a.deliveryNotes);
    }
    @Override public int hashCode() { return java.util.Objects.hash(recipientName, street, houseNumber, postalCode, municipality, province, country, deliveryNotes); }
}
