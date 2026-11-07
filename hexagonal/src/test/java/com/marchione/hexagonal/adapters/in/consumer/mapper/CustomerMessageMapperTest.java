package com.marchione.hexagonal.adapters.in.consumer.mapper;

import com.marchione.hexagonal.adapters.in.consumer.message.CustomerMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomerMessageMapperTest {

    private final CustomerMessageMapper mapper = new CustomerMessageMapperImpl();

    @Test
    void mapsValidCpfFromKafkaMessage() {
        var message = new CustomerMessage();
        message.setValidCpf(true);

        var customer = mapper.toCustomer(message);

        assertTrue(customer.isValidCpf());
    }
}
