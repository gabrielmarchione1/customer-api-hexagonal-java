package com.marchione.hexagonal.application.ports.in;

import com.marchione.hexagonal.application.core.domain.Customer;

public interface UpdateCustomerInputPort {

    void update(Customer customer, String zipCode);
}
