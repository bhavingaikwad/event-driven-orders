package com.bhavin.orders.order;

import com.bhavin.orders.contracts.EventEnvelope;
import com.bhavin.orders.contracts.EventType;
import com.bhavin.orders.contracts.events.OrderCreated;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

@Service
public class OrderService {

    private static final String PRODUCER = "order-service";

    private final OrderRepository orderRepository;
    private final OutboxRepository outboxRepository;
    private final KafkaEventSender kafkaEventSender;
    private final ObjectMapper objectMapper;
    private final PublishMode publishMode;

    public OrderService(OrderRepository orderRepository,
                        OutboxRepository outboxRepository,
                        KafkaEventSender kafkaEventSender,
                        ObjectMapper objectMapper,
                        @Value("${orders.publish-mode:OUTBOX}") PublishMode publishMode) {
        this.orderRepository = orderRepository;
        this.outboxRepository = outboxRepository;
        this.kafkaEventSender = kafkaEventSender;
        this.objectMapper = objectMapper;
        this.publishMode = publishMode;
    }

    @Transactional
    public Order create(CreateOrderRequest request) {
        List<OrderItem> items = request.items().stream()
                .map(i -> new OrderItem(i.sku(), i.quantity(), i.unitPrice()))
                .toList();
        Order order = orderRepository.save(
                Order.create(request.customerId(), request.customerRegion(), request.currency(), items));
        publishOrderCreated(order);
        return order;
    }

    @Transactional(readOnly = true)
    public Order get(UUID id) {
        return orderRepository.findById(id).orElseThrow(() -> new OrderNotFoundException(id));
    }

    private void publishOrderCreated(Order order) {
        OrderCreated payload = new OrderCreated(
                order.getId(), order.getCustomerId(), order.getCustomerRegion(),
                order.getItems().stream()
                        .map(i -> new OrderCreated.Item(i.getSku(), i.getQuantity(), i.getUnitPrice()))
                        .toList(),
                order.getTotalAmount(), order.getCurrency());
        EventEnvelope<OrderCreated> envelope =
                EventEnvelope.of(EventType.ORDER_CREATED, order.getCorrelationId(), PRODUCER, payload);
        String json = objectMapper.writeValueAsString(envelope);
        String key = order.getId().toString();
        String topic = EventType.ORDER_CREATED.topic();

        if (publishMode == PublishMode.OUTBOX) {
            // Same transaction as the order: both commit or neither does.
            outboxRepository.insert(envelope.eventId(), order.getId(), envelope.eventType(), topic, json);
        } else {
            // UNSAFE ON PURPOSE: publish after the DB commit. If this fails, the order is already saved.
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    kafkaEventSender.sendAndWait(topic, key, json);
                }
            });
        }
    }
}
