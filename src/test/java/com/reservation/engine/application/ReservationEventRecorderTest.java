package com.reservation.engine.application;

import com.reservation.engine.domain.ReservationStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReservationEventRecorderTest {
    @Test
    void eventTypesAreDerivedFromTargetStatus() {
        assertThat(ReservationEventRecorder.eventType(ReservationStatus.HELD)).isEqualTo("ReservationHeld");
        assertThat(ReservationEventRecorder.eventType(ReservationStatus.PAYMENT_PENDING)).isEqualTo("ReservationPaymentPending");
        assertThat(ReservationEventRecorder.eventType(ReservationStatus.CONFIRMED)).isEqualTo("ReservationConfirmed");
    }
}
