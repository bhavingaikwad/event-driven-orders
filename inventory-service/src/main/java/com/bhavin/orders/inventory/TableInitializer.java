package com.bhavin.orders.inventory;

import jakarta.annotation.PostConstruct;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

/** Creates the tables and seeds SIMULATED stock. Runs before the Kafka listener starts. */
@Component
public class TableInitializer {

    private static final Logger log = LoggerFactory.getLogger(TableInitializer.class);

    private final DynamoDbClient dynamo;
    private final InventoryProperties properties;

    public TableInitializer(DynamoDbClient dynamo, InventoryProperties properties) {
        this.dynamo = dynamo;
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        createTableIfMissing(DynamoTables.STOCK, "sku");
        createTableIfMissing(DynamoTables.RESERVATIONS, "orderId");
        properties.getSeedStock().forEach(this::seed);
    }

    private void createTableIfMissing(String table, String hashKey) {
        try {
            dynamo.describeTable(DescribeTableRequest.builder().tableName(table).build());
        } catch (ResourceNotFoundException e) {
            dynamo.createTable(CreateTableRequest.builder()
                    .tableName(table)
                    .attributeDefinitions(AttributeDefinition.builder()
                            .attributeName(hashKey).attributeType(ScalarAttributeType.S).build())
                    .keySchema(KeySchemaElement.builder()
                            .attributeName(hashKey).keyType(KeyType.HASH).build())
                    .billingMode(BillingMode.PAY_PER_REQUEST)
                    .build());
            dynamo.waiter().waitUntilTableExists(DescribeTableRequest.builder().tableName(table).build());
            log.info("Created DynamoDB table {}", table);
        }
    }

    private void seed(String sku, Integer quantity) {
        try {
            dynamo.putItem(PutItemRequest.builder()
                    .tableName(DynamoTables.STOCK)
                    .item(Map.of(
                            "sku", AttributeValue.builder().s(sku).build(),
                            "available", AttributeValue.builder().n(String.valueOf(quantity)).build()))
                    .conditionExpression("attribute_not_exists(sku)")
                    .build());
            log.info("Seeded simulated stock {} = {}", sku, quantity);
        } catch (ConditionalCheckFailedException e) {
            log.info("Stock for {} already exists, not reseeding", sku);
        }
    }
}
