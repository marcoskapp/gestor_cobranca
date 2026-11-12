package br.com.gestorcobranca.shared.events;

import br.com.gestorcobranca.shared.events.incentive.NegotiatorTierChanged;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contrato do {@link NegotiatorTierChanged} (distribuicao-por-desempenho.md §11.1): ida e volta pelo registro de
 * publicação, com e sem nível, e leitura do formato gravado (datas ISO, nível como texto).
 */
class NegotiatorTierChangedCompatibilityTest {

    private final JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();

    @Test
    void roundTripWithTier() {
        NegotiatorTierChanged original = new NegotiatorTierChanged(UUID.randomUUID(), UUID.randomUUID(),
                Instant.parse("2026-11-12T12:00:00Z"), UUID.randomUUID(), "A", LocalDate.of(2026, 11, 12),
                UUID.randomUUID());

        assertThat(mapper.readValue(mapper.writeValueAsString(original), NegotiatorTierChanged.class)).isEqualTo(original);
    }

    @Test
    void roundTripWithoutTier() {
        NegotiatorTierChanged original = new NegotiatorTierChanged(UUID.randomUUID(), UUID.randomUUID(),
                Instant.parse("2026-11-12T12:00:00Z"), UUID.randomUUID(), null, LocalDate.of(2026, 11, 12),
                UUID.randomUUID());

        assertThat(mapper.readValue(mapper.writeValueAsString(original), NegotiatorTierChanged.class).tier()).isNull();
    }

    @Test
    void readsStoredFormat() {
        UUID user = UUID.randomUUID();
        UUID publication = UUID.randomUUID();
        NegotiatorTierChanged e = mapper.readValue("""
                {"eventId":"%s","tenantId":"%s","occurredAt":"2026-11-12T12:00:00Z","userId":"%s","tier":"C",
                 "validFrom":"2026-11-12","publicationId":"%s"}""".formatted(UUID.randomUUID(), UUID.randomUUID(), user,
                publication), NegotiatorTierChanged.class);

        assertThat(e.userId()).isEqualTo(user);
        assertThat(e.tier()).isEqualTo("C");
        assertThat(e.validFrom()).isEqualTo(LocalDate.of(2026, 11, 12));
        assertThat(e.publicationId()).isEqualTo(publication);
    }
}
