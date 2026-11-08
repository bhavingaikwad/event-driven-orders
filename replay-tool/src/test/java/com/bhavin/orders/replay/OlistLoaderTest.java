package com.bhavin.orders.replay;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OlistLoaderTest {

    @TempDir
    Path dir;

    private void write(String name, String content) throws IOException {
        Files.writeString(dir.resolve(name), content);
    }

    private void writeSample() throws IOException {
        write("olist_customers_dataset.csv",
                "\"customer_id\",\"customer_unique_id\",\"customer_zip_code_prefix\",\"customer_city\",\"customer_state\"\n"
                        + "\"c1\",\"u1\",\"14409\",franca,SP\n"
                        + "\"c2\",\"u2\",\"01000\",rio,RJ\n");
        write("olist_order_items_dataset.csv",
                "\"order_id\",\"order_item_id\",\"product_id\",\"seller_id\",\"shipping_limit_date\",\"price\",\"freight_value\"\n"
                        + "\"o1\",1,\"p1\",\"s1\",2017-11-24 10:00:00,58.90,13.29\n"
                        + "\"o1\",2,\"p1\",\"s1\",2017-11-24 10:00:00,58.90,13.29\n"
                        + "\"o1\",3,\"p2\",\"s1\",2017-11-24 10:00:00,10.00,5.00\n"
                        + "\"o2\",1,\"p3\",\"s2\",2017-11-24 10:00:00,7.50,2.00\n"
                        + "\"o4\",1,\"p3\",\"s2\",2017-11-24 10:00:00,not-a-price,2.00\n");
        write("olist_orders_dataset.csv",
                "\"order_id\",\"customer_id\",\"order_status\",\"order_purchase_timestamp\",\"order_approved_at\"\n"
                        + "o2,\"c2\",delivered,2017-11-24 09:00:00,2017-11-24 09:05:00\n"
                        + "o1,\"c1\",delivered,2017-11-24 08:00:00,2017-11-24 08:05:00\n"
                        + "o3,\"c1\",canceled,2017-11-24 07:00:00,\n"
                        + "o4,\"c1\",delivered,2017-11-24 06:00:00,2017-11-24 06:05:00\n");
    }

    @Test
    void joinsOrdersItemsAndCustomersOldestFirst() throws IOException {
        writeSample();

        OlistLoader.Loaded loaded = OlistLoader.load(dir);

        assertThat(loaded.orders()).extracting(ReplayOrder::orderId).containsExactly("o1", "o2");
        ReplayOrder first = loaded.orders().get(0);
        assertThat(first.customerId()).isEqualTo("u1");
        assertThat(first.region()).isEqualTo("SP");
        assertThat(first.purchasedAt()).isEqualTo("2017-11-24T08:00:00");
    }

    @Test
    void mergesRowsOfTheSameProductIntoOneLine() throws IOException {
        writeSample();

        ReplayOrder first = OlistLoader.load(dir).orders().get(0);

        assertThat(first.lines()).containsExactly(
                new ReplayOrder.Line("p1", 2, "58.90"),
                new ReplayOrder.Line("p2", 1, "10.00"));
    }

    @Test
    void countsOrdersWithoutItemsAndMalformedRows() throws IOException {
        writeSample();

        OlistLoader.Loaded loaded = OlistLoader.load(dir);

        assertThat(loaded.withoutItems()).isEqualTo(2);
        assertThat(loaded.malformed()).isEqualTo(1);
    }

    @Test
    void splitRemovesQuotesAndKeepsEmptyFields() {
        assertThat(OlistLoader.split("a,\"b\",,c")).containsExactly("a", "b", "", "c");
    }
}
