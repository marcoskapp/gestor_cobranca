package br.com.gestorcobranca.assistant.domain.service;

import br.com.gestorcobranca.assistant.domain.model.AssistantMode;
import br.com.gestorcobranca.assistant.domain.model.AssistantSession;
import br.com.gestorcobranca.assistant.domain.model.PolicySettings;
import br.com.gestorcobranca.assistant.domain.model.SessionMemory;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ContextBuilderTest {

    private static final PolicySettings POLICY = PolicySettings.defaults(AssistantMode.AUTONOMOUS);
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 24);

    private static SessionMemory memory() {
        UUID sim = UUID.randomUUID();
        return SessionMemory.EMPTY
                .withDebt(new SessionMemory.DebtSummary(new BigDecimal("1500.00"), 2, 60, TODAY))
                .withOffers(sim, List.of(
                        new SessionMemory.OfferMemo("O1", sim, 0, 1, new BigDecimal("1500.00"), new BigDecimal("1500.00"),
                                new BigDecimal("1500.00"), TODAY, BigDecimal.ZERO, List.of(), true, true),
                        new SessionMemory.OfferMemo("O2", sim, 0, 6, new BigDecimal("1500.00"), null,
                                new BigDecimal("250.00"), TODAY, BigDecimal.ZERO, List.of(), false, false)));
    }

    private static AssistantSession session() {
        return new AssistantSession(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, null, true, "{}");
    }

    @Test
    void beforeIdentityNothingAboutTheDebtGoesToTheModel() {
        ModelContext ctx = ContextBuilder.build(POLICY, session(), memory(), "Maria da Silva", 60L, "OPEN", "WHATSAPP",
                TODAY, false);
        assertThat(ctx.identity()).isEqualTo("PENDING");
        assertThat(ctx.firstName()).isNull();
        assertThat(ctx.debt()).isNull();
        assertThat(ctx.offers()).isEmpty();
        assertThat(ctx.collectionCase()).isNull();
        assertThat(ctx.rules()).anyMatch(r -> r.contains("Identidade não confirmada"));
    }

    @Test
    void afterIdentityOnlyFirstNameAndValidEngineOffers() {
        AssistantSession s = session();
        s.identityVerified(Instant.now());
        ModelContext ctx = ContextBuilder.build(POLICY, s, memory(), "maria da silva", 60L, "OPEN", "WHATSAPP", TODAY, false);
        assertThat(ctx.firstName()).isEqualTo("Maria");
        assertThat(ctx.debt().updatedTotal()).isEqualByComparingTo("1500.00");
        assertThat(ctx.offers()).extracting(ModelContext.OfferInfo::ref).containsExactly("O1");
        assertThat(ctx.toString()).doesNotContain("silva").doesNotContain(s.getDebtorId().toString())
                .doesNotContain(s.getCreditorId().toString());
    }

    @Test
    void anonymizedNameIsNotSent() {
        assertThat(ContextBuilder.firstName("Titular anonimizado")).isNull();
        assertThat(ContextBuilder.firstName("  ")).isNull();
    }
}
