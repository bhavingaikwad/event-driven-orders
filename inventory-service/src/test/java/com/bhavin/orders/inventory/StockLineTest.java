package com.bhavin.orders.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class StockLineTest {

    @Test
    void mergeSumsQuantitiesOfTheSameSku() {
        List<StockLine> merged = StockLine.merge(List.of(
                new StockLine("a", 2), new StockLine("b", 1), new StockLine("a", 3)));
        assertThat(merged).containsExactly(new StockLine("a", 5), new StockLine("b", 1));
    }

    @Test
    void mergeKeepsFirstSeenOrder() {
        List<StockLine> merged = StockLine.merge(List.of(
                new StockLine("z", 1), new StockLine("m", 1), new StockLine("a", 1)));
        assertThat(merged).extracting(StockLine::sku).containsExactly("z", "m", "a");
    }

    @Test
    void rejectsNonPositiveQuantity() {
        assertThatThrownBy(() -> new StockLine("a", 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
