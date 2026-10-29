package com.reservation.engine.domain;

import com.reservation.engine.domain.exception.InvalidStateTransitionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static com.reservation.engine.domain.ReservationStatus.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReservationStateMachineTest {

    @ParameterizedTest
    @CsvSource({
            "HELD,PAYMENT_PENDING", "HELD,CONFIRMED", "HELD,EXPIRED", "HELD,CANCELLED",
            "PAYMENT_PENDING,CONFIRMED", "PAYMENT_PENDING,HELD", "PAYMENT_PENDING,EXPIRED",
            "CONFIRMED,CANCELLED"
    })
    void allowsDocumentedTransitions(ReservationStatus from, ReservationStatus to) {
        assertThat(ReservationStateMachine.canTransition(from, to)).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
            "CONFIRMED,EXPIRED", "CONFIRMED,HELD", "CONFIRMED,PAYMENT_PENDING",
            "PAYMENT_PENDING,CANCELLED", "HELD,HELD",
            "CANCELLED,HELD", "CANCELLED,CONFIRMED", "EXPIRED,CONFIRMED", "EXPIRED,HELD", "EXPIRED,CANCELLED"
    })
    void rejectsOtherTransitions(ReservationStatus from, ReservationStatus to) {
        assertThat(ReservationStateMachine.canTransition(from, to)).isFalse();
        assertThatThrownBy(() -> ReservationStateMachine.assertTransition(from, to))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining(from.name()).hasMessageContaining(to.name());
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"CANCELLED", "EXPIRED"})
    void terminalStatesHaveNoExits(ReservationStatus terminal) {
        assertThat(terminal.isTerminal()).isTrue();
        assertThat(ReservationStateMachine.allowedFrom(terminal)).isEmpty();
        assertThat(terminal.holdsInventory()).isFalse();
    }

    @Test
    void liveStatesHoldInventory() {
        assertThat(HELD.holdsInventory()).isTrue();
        assertThat(PAYMENT_PENDING.holdsInventory()).isTrue();
        assertThat(CONFIRMED.holdsInventory()).isTrue();
    }
}
