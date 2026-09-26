package com.why.fulfillment.commerce.checkout.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class CheckoutOrderIdSerializationTest {
    // 2^53 + 1 cannot be represented exactly by a JavaScript Number.
    private static final long ORDER_ID = 9_007_199_254_740_993L;
    private static final String ORDER_ID_TEXT = "9007199254740993";
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void checkoutExposesExactOrderIdAsTextWithoutStringifyingMoney() throws Exception {
        CheckoutResultView result = new CheckoutResultView(
                ORDER_ID, "RESERVED", "created", new BigDecimal("19.90"), false);

        String body = mapper.writeValueAsString(result);
        JsonNode json = mapper.readTree(body);

        assertThat(json.get("orderId").isTextual()).isTrue();
        assertThat(json.get("orderId").textValue()).isEqualTo(ORDER_ID_TEXT);
        assertThat(json.get("totalAmount").isNumber()).isTrue();
        assertThat(json.get("replayed").isBoolean()).isTrue();
        assertThat(mapper.readValue(body, CheckoutResultView.class).orderId()).isEqualTo(ORDER_ID);
    }

    @Test
    void missingOrderIdStaysNullRatherThanTheStringNull() throws Exception {
        CheckoutResultView result = new CheckoutResultView(
                null, "UNKNOWN", "not settled", new BigDecimal("19.90"), false);

        assertThat(mapper.readTree(mapper.writeValueAsString(result)).get("orderId").isNull()).isTrue();
    }
}
