package com.bhavin.orders.inventory;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "inventory")
public class InventoryProperties {

    /** Simulated starting stock per sku. */
    private Map<String, Integer> seedStock = new LinkedHashMap<>();

    public Map<String, Integer> getSeedStock() {
        return seedStock;
    }

    public void setSeedStock(Map<String, Integer> seedStock) {
        this.seedStock = seedStock;
    }
}
