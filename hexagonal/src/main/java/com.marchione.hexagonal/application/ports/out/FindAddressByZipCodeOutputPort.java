package com.marchione.hexagonal.application.ports.out;

import com.marchione.hexagonal.application.core.domain.Address;

public interface FindAddressByZipCodeOutputPort {

    Address find(String zipCode);
}
