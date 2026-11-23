package br.com.gestorcobranca.shared.events;

import br.com.gestorcobranca.shared.events.incentive.CommissionStatementsChanged;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contrato do {@link CommissionStatementsChanged} (margem-por-carteira.md §4.2): ida e volta pelo registro de
 * publicação e leitura do formato gravado (só assessoria e período).
 */
class CommissionStatementsChangedCompatibilityTest {

    private final JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();

    @Test
    void roundTrip() {
        CommissionStatementsChanged original = new CommissionStatementsChanged(UUID.randomUUID(), UUID.randomUUID(),
                Instant.parse("2026-11-23T12:00:00Z"), LocalDate.of(2026, 10, 1));

        assertThat(mapper.readValue(mapper.writeValueAsString(original), CommissionStatementsChanged.class))
                .isEqualTo(original);
    }

    @Test
    void readsStoredFormat() {
        String stored = """
                {"eventId":"8d6f1c1e-0000-4000-8000-000000000001","tenantId":"8d6f1c1e-0000-4000-8000-000000000002",
                 "occurredAt":"2026-11-23T12:00:00Z","periodStart":"2026-10-01"}""";

        assertThat(mapper.readValue(stored, CommissionStatementsChanged.class).periodStart())
                .isEqualTo(LocalDate.of(2026, 10, 1));
    }
}
