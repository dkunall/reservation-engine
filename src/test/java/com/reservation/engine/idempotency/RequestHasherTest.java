package com.reservation.engine.idempotency;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RequestHasherTest {

    private final RequestHasher hasher = new RequestHasher();

    record Body(UUID resourceId, Integer quantity, List<String> unitCodes, Instant startsAt) {
    }

    @Test
    void sameContentSameHash() {
        UUID id = UUID.randomUUID();
        Instant t = Instant.parse("2030-01-01T00:00:00Z");
        String a = hasher.hash("CREATE", "", new Body(id, 2, List.of("A"), t));
        String b = hasher.hash("CREATE", "", new Body(id, 2, List.of("A"), t));
        assertThat(a).isEqualTo(b).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void mapKeyOrderDoesNotMatter() {
        Map<String, Object> m1 = new LinkedHashMap<>();
        m1.put("a", 1);
        m1.put("b", 2);
        Map<String, Object> m2 = new LinkedHashMap<>();
        m2.put("b", 2);
        m2.put("a", 1);
        assertThat(hasher.hash("OP", "t", m1)).isEqualTo(hasher.hash("OP", "t", m2));
    }

    @Test
    void differentPayloadOperationOrTargetChangesHash() {
        UUID id = UUID.randomUUID();
        String base = hasher.hash("CREATE", "", new Body(id, 2, List.of(), null));
        assertThat(hasher.hash("CREATE", "", new Body(id, 3, List.of(), null))).isNotEqualTo(base);
        assertThat(hasher.hash("CANCEL", "", new Body(id, 2, List.of(), null))).isNotEqualTo(base);
        assertThat(hasher.hash("CREATE", "x", new Body(id, 2, List.of(), null))).isNotEqualTo(base);
    }

    @Test
    void nullBodyIsHashable() {
        assertThat(hasher.hash("CONFIRM", "id", null)).isEqualTo(hasher.hash("CONFIRM", "id", null));
    }
}
