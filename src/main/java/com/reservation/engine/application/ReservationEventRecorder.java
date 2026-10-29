package com.reservation.engine.application;

import com.reservation.engine.domain.Reservation;
import com.reservation.engine.domain.ReservationStatus;
import com.reservation.engine.domain.StateTransition;
import com.reservation.engine.observability.Correlation;
import com.reservation.engine.outbox.OutboxWriter;
import com.reservation.engine.persistence.ReservationAudit;
import com.reservation.engine.persistence.ReservationAuditRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/** Persists the aggregate's recorded transitions as audit rows + outbox events, in the same transaction. */
@Component
public class ReservationEventRecorder {
    private static final Logger log = LoggerFactory.getLogger(ReservationEventRecorder.class);

    private final ReservationAuditRepository auditRepository;
    private final OutboxWriter outbox;

    public ReservationEventRecorder(ReservationAuditRepository auditRepository, OutboxWriter outbox) {
        this.auditRepository = auditRepository;
        this.outbox = outbox;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Reservation r) {
        for (StateTransition t : r.pullTransitions()) {
            auditRepository.save(new ReservationAudit(r.getId(), t.from(), t.to(), t.actor(), t.reason(),
                    Correlation.currentId(), t.at()));
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("reservationId", r.getId().toString());
            payload.put("userId", r.getUserId());
            payload.put("resourceId", r.getResourceId().toString());
            payload.put("resourceType", r.getResourceType());
            payload.put("fromStatus", t.from() == null ? null : t.from().name());
            payload.put("status", t.to().name());
            payload.put("quantity", r.getQuantity());
            payload.put("unitCodes", r.getUnitCodes());
            payload.put("totalAmount", r.getTotal().amount().toPlainString());
            payload.put("currency", r.getTotal().currency());
            payload.put("occurredAt", t.at().toString());
            outbox.append("Reservation", r.getId(), eventType(t.to()), payload);
            log.info("reservation transition id={} from={} to={} actor={} reason={}",
                    r.getId(), t.from(), t.to(), t.actor(), t.reason());
        }
    }

    static String eventType(ReservationStatus to) {
        StringBuilder sb = new StringBuilder("Reservation");
        for (String part : to.name().split("_")) {
            sb.append(part.charAt(0)).append(part.substring(1).toLowerCase());
        }
        return sb.toString();
    }
}
