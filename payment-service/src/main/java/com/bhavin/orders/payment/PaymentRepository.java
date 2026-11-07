package com.bhavin.orders.payment;

import java.math.BigDecimal;
import java.sql.Types;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PaymentRepository {

    private final JdbcClient jdbc;

    public PaymentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void saveOrderAmount(UUID orderId, BigDecimal amount, String currency) {
        jdbc.sql("""
                INSERT INTO order_amounts (order_id, amount, currency)
                VALUES (:orderId, :amount, :currency)
                ON CONFLICT (order_id) DO NOTHING
                """)
                .param("orderId", orderId)
                .param("amount", amount)
                .param("currency", currency)
                .update();
    }

    public Optional<OrderAmount> findOrderAmount(UUID orderId) {
        return jdbc.sql("SELECT amount, currency FROM order_amounts WHERE order_id = :orderId")
                .param("orderId", orderId)
                .query((rs, rowNum) -> new OrderAmount(rs.getBigDecimal("amount"), rs.getString("currency")))
                .optional();
    }

    public Optional<Payment> findByOrderId(UUID orderId) {
        return jdbc.sql("""
                SELECT payment_id, order_id, status, amount, currency, failure_reason
                FROM payments WHERE order_id = :orderId
                """)
                .param("orderId", orderId)
                .query((rs, rowNum) -> new Payment(
                        rs.getObject("payment_id", UUID.class),
                        rs.getObject("order_id", UUID.class),
                        PaymentStatus.valueOf(rs.getString("status")),
                        rs.getBigDecimal("amount"),
                        rs.getString("currency"),
                        rs.getString("failure_reason")))
                .optional();
    }

    /** True if this call stored the payment, false if a payment for the order already existed. */
    public boolean insertIfAbsent(Payment payment) {
        int rows = jdbc.sql("""
                INSERT INTO payments (payment_id, order_id, status, amount, currency, failure_reason)
                VALUES (:paymentId, :orderId, :status, :amount, :currency, :reason)
                ON CONFLICT (order_id) DO NOTHING
                """)
                .param("paymentId", payment.paymentId())
                .param("orderId", payment.orderId())
                .param("status", payment.status().name())
                .param("amount", payment.amount())
                .param("currency", payment.currency())
                .param("reason", payment.failureReason(), Types.VARCHAR)
                .update();
        return rows == 1;
    }
}
