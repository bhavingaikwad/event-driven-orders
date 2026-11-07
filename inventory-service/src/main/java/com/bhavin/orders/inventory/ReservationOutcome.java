package com.bhavin.orders.inventory;

import java.util.UUID;

public sealed interface ReservationOutcome {

    record Reserved(UUID reservationId) implements ReservationOutcome {
    }

    record Rejected(String reason) implements ReservationOutcome {
    }
}
