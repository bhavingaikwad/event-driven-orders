package com.bhavin.orders.replay;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReplayOrderTest {

    @Test
    void buildsTheRequestBodyOfOrderService() {
        ReplayOrder order = new ReplayOrder("o1", LocalDateTime.parse("2017-11-24T22:04:11"), "cust-1", "SP",
                List.of(new ReplayOrder.Line("p1", 2, "58.90"), new ReplayOrder.Line("p2", 1, "10.00")));

        assertThat(order.toJson()).isEqualTo(
                "{\"customerId\":\"cust-1\",\"customerRegion\":\"SP\",\"currency\":\"BRL\",\"items\":["
                        + "{\"sku\":\"p1\",\"quantity\":2,\"unitPrice\":58.90},"
                        + "{\"sku\":\"p2\",\"quantity\":1,\"unitPrice\":10.00}]}");
    }
}
