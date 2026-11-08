package com.bhavin.orders.replay;

import java.time.LocalDateTime;
import java.util.List;

/** One real Olist order, ready to be sent to order-service. */
public record ReplayOrder(String orderId, LocalDateTime purchasedAt, String customerId, String region,
                          List<Line> lines) {

    /** One product in the order. The price keeps the original text of the dataset, for example "58.90". */
    public record Line(String sku, int quantity, String unitPrice) {
    }

    /** The request body for POST /orders. Every value was validated by the loader, so no escaping is needed. */
    public String toJson() {
        StringBuilder json = new StringBuilder(256);
        json.append("{\"customerId\":\"").append(customerId)
                .append("\",\"customerRegion\":\"").append(region)
                .append("\",\"currency\":\"BRL\",\"items\":[");
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"sku\":\"").append(line.sku())
                    .append("\",\"quantity\":").append(line.quantity())
                    .append(",\"unitPrice\":").append(line.unitPrice())
                    .append('}');
        }
        return json.append("]}").toString();
    }
}
