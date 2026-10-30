package com.marchione.hexagonal.application.ports.in;

import com.marchione.hexagonal.application.core.domain.Customer;

public interface InsertCustomerInputPort {

    void insert(Customer customer, String zipCode);

}
