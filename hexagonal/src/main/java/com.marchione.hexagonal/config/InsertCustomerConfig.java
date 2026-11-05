package com.marchione.hexagonal.config;

import com.marchione.hexagonal.adapters.out.FindAddressByZipCodeAdapter;
import com.marchione.hexagonal.adapters.out.InsertCustomerAdapter;
import com.marchione.hexagonal.adapters.out.SendCpfValidationAdapter;
import com.marchione.hexagonal.application.core.usecase.InsertCustomerUseCase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class InsertCustomerConfig {

    @Bean
    public InsertCustomerUseCase insertCustomerUseCase(
            FindAddressByZipCodeAdapter findAddressByZipCodeAdapter,
            InsertCustomerAdapter insertCustomerAdapter,
            SendCpfValidationAdapter sendCpfValidationAdapter
    ) {
        return new InsertCustomerUseCase(findAddressByZipCodeAdapter, insertCustomerAdapter, sendCpfValidationAdapter);
    }
}
