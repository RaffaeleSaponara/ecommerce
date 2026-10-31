package com.TreDL.ecommerce.dto;
import com.TreDL.ecommerce.model.AddressSnapshot;
import com.TreDL.ecommerce.service.CheckoutException;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
public record AddressRequest(String recipientName, String street, String houseNumber, String postalCode,
                             String municipality, String province, String country, String deliveryNotes, boolean defaultAddress) {
    public AddressSnapshot validated() {
        String name=required(recipientName,150), road=required(street,180), number=required(houseNumber,20);
        String postal=required(postalCode,5), city=required(municipality,100), region=required(province,2);
        String nation=country==null ? "IT" : country.strip();
        String notes=deliveryNotes==null ? null : deliveryNotes.strip();
        if (!postal.matches("[0-9]{5}") || !region.matches("[A-Z]{2}") || !nation.equals("IT") || (notes!=null && notes.length()>500))
            throw new CheckoutException(BAD_REQUEST,"Indirizzo italiano non valido");
        return new AddressSnapshot(name,road,number,postal,city,region,nation,notes);
    }
    private static String required(String value,int max) {
        if(value==null || value.isBlank() || value.strip().length()>max)
            throw new CheckoutException(BAD_REQUEST,"Campi indirizzo obbligatori o troppo lunghi");
        return value.strip();
    }
}
