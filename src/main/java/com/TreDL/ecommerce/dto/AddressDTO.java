package com.TreDL.ecommerce.dto;
import com.TreDL.ecommerce.model.CustomerAddress;
public record AddressDTO(Long id,String recipientName,String street,String houseNumber,String postalCode,
                         String municipality,String province,String country,String deliveryNotes,boolean defaultAddress) {
    public static AddressDTO from(CustomerAddress entity) {
        var a=entity.getAddress();
        return new AddressDTO(entity.getId(),a.getRecipientName(),a.getStreet(),a.getHouseNumber(),a.getPostalCode(),
                a.getMunicipality(),a.getProvince(),a.getCountry(),a.getDeliveryNotes(),entity.isDefaultAddress());
    }
}
