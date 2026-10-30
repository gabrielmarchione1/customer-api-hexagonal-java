package com.marchione.hexagonal.application.ports.out;

import com.marchione.hexagonal.application.core.domain.Customer;

import java.util.Optional;

public interface FindCustomerByIdOutputPort {

    Optional<Customer> find(String id);
}
