package com.why.fulfillment.order;

import com.why.fulfillment.api.inventory.InventoryClient;
import com.why.fulfillment.api.order.OrderClient;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.openfeign.support.SpringMvcContract;

import static org.assertj.core.api.Assertions.assertThatCode;

class FeignContractTest {

    @Test
    void serviceContractsCanBeParsedByOpenFeign() {
        SpringMvcContract contract = new SpringMvcContract();

        assertThatCode(() -> contract.parseAndValidateMetadata(InventoryClient.class))
                .doesNotThrowAnyException();
        assertThatCode(() -> contract.parseAndValidateMetadata(OrderClient.class))
                .doesNotThrowAnyException();
    }
}
