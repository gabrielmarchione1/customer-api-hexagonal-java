package com.marchione.hexagonal.application.ports.in;

import com.marchione.hexagonal.application.core.domain.Customer;

public interface FindCustomerByIdInputPort {

    Customer find(String id);

}
