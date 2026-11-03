package com.marchione.hexagonal.application.ports.out;

import com.marchione.hexagonal.application.core.domain.Customer;

public interface UpdateCustomerOutputPort {

    void update(Customer customer);

}
