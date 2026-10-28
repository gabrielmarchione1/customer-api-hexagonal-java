package com.marchione.hexagonal.adapters.out.client.mapper;

import com.marchione.hexagonal.adapters.out.client.response.AddressResponse;
import com.marchione.hexagonal.application.core.domain.Address;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface AddressResponseMapper {

    Address toAddress(AddressResponse addressResponse);

}