package com.bhavin.orders.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bhavin.orders.contracts.events.OrderCancelled;
import com.bhavin.orders.contracts.events.OrderConfirmed;
import com.bhavin.orders.contracts.events.OrderCreated;
import com.bhavin.orders.contracts.events.PaymentFailed;
import com.bhavin.orders.contracts.events.PaymentSucceeded;
import com.bhavin.orders.contracts.events.StockRejected;
import com.bhavin.orders.contracts.events.StockReserved;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The wire format is the contract between services. These golden strings are written by hand on purpose:
 * if a field is renamed, retyped or removed, this test fails and the change has to be a conscious,
 * versioned one (see the rule in EventType).
 */
class EventWireFormatTest {

    private static final UUID EVENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CORRELATION_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ORDER_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID RESERVATION_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID PAYMENT_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final Instant OCCURRED_AT = Instant.parse("2026-01-02T03:04:05Z");

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    /** The same tolerance the services configure: unknown fields are ignored. */
    private static final ObjectMapper TOLERANT = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    static Stream<Arguments> cases() {
        String order = "\"orderId\":\"" + ORDER_ID + "\"";
        return Stream.of(
                Arguments.of(EventType.ORDER_CREATED, "OrderCreated", "order-events",
                        new OrderCreated(ORDER_ID, "cust-1", "US",
                                List.of(new OrderCreated.Item("sku-1", 2, new BigDecimal("10.00"))),
                                new BigDecimal("20.00"), "USD"),
                        "{" + order + ",\"customerId\":\"cust-1\",\"customerRegion\":\"US\","
                                + "\"items\":[{\"sku\":\"sku-1\",\"quantity\":2,\"unitPrice\":10.00}],"
                                + "\"totalAmount\":20.00,\"currency\":\"USD\"}"),
                Arguments.of(EventType.STOCK_RESERVED, "StockReserved", "inventory-events",
                        new StockReserved(ORDER_ID, RESERVATION_ID),
                        "{" + order + ",\"reservationId\":\"" + RESERVATION_ID + "\"}"),
                Arguments.of(EventType.STOCK_REJECTED, "StockRejected", "inventory-events",
                        new StockRejected(ORDER_ID, "Insufficient stock"),
                        "{" + order + ",\"reason\":\"Insufficient stock\"}"),
                Arguments.of(EventType.PAYMENT_SUCCEEDED, "PaymentSucceeded", "payment-events",
                        new PaymentSucceeded(ORDER_ID, PAYMENT_ID, new BigDecimal("20.00"), "USD"),
                        "{" + order + ",\"paymentId\":\"" + PAYMENT_ID + "\",\"amount\":20.00,\"currency\":\"USD\"}"),
                Arguments.of(EventType.PAYMENT_FAILED, "PaymentFailed", "payment-events",
                        new PaymentFailed(ORDER_ID, "Card declined (simulated)"),
                        "{" + order + ",\"reason\":\"Card declined (simulated)\"}"),
                Arguments.of(EventType.ORDER_CONFIRMED, "OrderConfirmed", "order-events",
                        new OrderConfirmed(ORDER_ID),
                        "{" + order + "}"),
                Arguments.of(EventType.ORDER_CANCELLED, "OrderCancelled", "order-events",
                        new OrderCancelled(ORDER_ID, "Payment failed: declined"),
                        "{" + order + ",\"reason\":\"Payment failed: declined\"}"));
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("cases")
    void serializedFormMatchesTheGoldenJson(EventType type, String name, String topic,
                                            Object payload, String payloadJson) {
        assertThat(type.typeName()).isEqualTo(name);
        assertThat(type.topic()).isEqualTo(topic);
        assertThat(type.schemaVersion()).isEqualTo(1);

        EventEnvelope<Object> envelope = new EventEnvelope<>(EVENT_ID, type.typeName(),
                type.schemaVersion(), CORRELATION_ID, OCCURRED_AT, "test-producer", payload);

        assertThat(MAPPER.readTree(MAPPER.writeValueAsString(envelope)))
                .isEqualTo(MAPPER.readTree(envelopeJson(name, payloadJson)));
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("cases")
    void goldenJsonReadsBackIntoTheSameEvent(EventType type, String name, String topic,
                                             Object payload, String payloadJson) {
        JavaType envelopeType = MAPPER.getTypeFactory()
                .constructParametricType(EventEnvelope.class, type.payloadClass());

        EventEnvelope<Object> read = MAPPER.readValue(envelopeJson(name, payloadJson), envelopeType);

        assertThat(read.eventId()).isEqualTo(EVENT_ID);
        assertThat(read.eventType()).isEqualTo(name);
        assertThat(read.schemaVersion()).isEqualTo(1);
        assertThat(read.correlationId()).isEqualTo(CORRELATION_ID);
        assertThat(read.occurredAt()).isEqualTo(OCCURRED_AT);
        assertThat(read.producer()).isEqualTo("test-producer");
        assertThat(read.payload()).isEqualTo(payload);
    }

    @Test
    void unknownFieldsAreIgnoredSoProducersCanAddOptionalFields() {
        String json = envelopeJson("StockReserved",
                "{\"orderId\":\"" + ORDER_ID + "\",\"reservationId\":\"" + RESERVATION_ID
                        + "\",\"addedLater\":\"x\"}")
                .replace("\"producer\":\"test-producer\"", "\"producer\":\"test-producer\",\"trace\":\"x\"");
        JavaType type = TOLERANT.getTypeFactory()
                .constructParametricType(EventEnvelope.class, StockReserved.class);

        EventEnvelope<StockReserved> read = TOLERANT.readValue(json, type);

        assertThat(read.payload()).isEqualTo(new StockReserved(ORDER_ID, RESERVATION_ID));
    }

    @Test
    void aPayloadMissingARequiredFieldFailsWithAJacksonException() {
        String json = envelopeJson("OrderConfirmed", "{}");
        JavaType type = TOLERANT.getTypeFactory()
                .constructParametricType(EventEnvelope.class, OrderConfirmed.class);

        assertThatThrownBy(() -> TOLERANT.readValue(json, type)).isInstanceOf(JacksonException.class);
    }

    private static String envelopeJson(String eventType, String payloadJson) {
        return """
                {"eventId":"%s","eventType":"%s","schemaVersion":1,"correlationId":"%s",\
                "occurredAt":"%s","producer":"test-producer","payload":%s}
                """.formatted(EVENT_ID, eventType, CORRELATION_ID, OCCURRED_AT, payloadJson);
    }
}
