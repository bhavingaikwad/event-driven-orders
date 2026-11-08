package com.bhavin.orders.inventory;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Simulation control: set the SIMULATED stock of many skus. No authentication, so disable it before any public deploy. */
@RestController
public class StockAdminController {

    private final StockSeeder seeder;

    public StockAdminController(StockSeeder seeder) {
        this.seeder = seeder;
    }

    @PutMapping("/admin/stock")
    public Map<String, Object> seed(@RequestBody Map<String, Integer> stock) {
        try {
            return Map.of("seeded", seeder.seed(stock));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
}
