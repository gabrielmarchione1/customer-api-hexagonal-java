package com.marchione.hexagonal.application.ports.out;

import com.marchione.hexagonal.application.core.domain.Customer;

public interface InsertCustomerOutputPort {

    void insert(Customer customer);

}
