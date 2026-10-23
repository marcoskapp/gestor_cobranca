package br.com.gestorcobranca.negotiation.domain.model.proposal;

import br.com.gestorcobranca.negotiation.domain.model.agreement.AcceptanceEvidence;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Ciclo de vida da proposta (§5). */
class ProposalTest {

    static final LocalDate TODAY = LocalDate.of(2026, 11, 2);
    static final Instant NOW = Instant.parse("2026-11-02T13:00:00Z");
    final UUID proposer = UUID.randomUUID();

    Proposal proposal(ProposalStatus initial) {
        return new Proposal(new Proposal.Draft(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, null,
                UUID.randomUUID(), UUID.randomUUID(), 1, null, TODAY, "USER", proposer, "JUNIOR", 0, null, null, false,
                "PHONE", List.of(UUID.randomUUID()), "{}", 1, null, TODAY.plusDays(3), Money.of("100.00"), Money.ZERO,
                Money.of("100.00"), Money.of("100.00"), Money.ZERO, Money.of("100.00"), BigDecimal.ZERO, 30, "f".repeat(64),
                BigDecimal.ZERO, new BigDecimal("100"), "[]", "{}", TODAY.plusDays(3), null, 0, null), initial);
    }

    AcceptanceEvidence evidence() {
        return new AcceptanceEvidence("WHATSAPP", NOW, "USER", proposer, "msg-1", null, null, UUID.randomUUID(), "k",
                "b".repeat(64), 10);
    }

    @Test
    void withinAuthorityGoesStraightToAcceptanceAndFormalization() {
        Proposal p = proposal(ProposalStatus.DRAFT);
        assertThatThrownBy(() -> p.formalized(UUID.randomUUID(), TODAY, NOW)).isInstanceOf(ConflictException.class);
        p.accept(evidence(), TODAY);
        assertThat(p.getStatus()).isEqualTo(ProposalStatus.ACCEPTED);
        assertThat(p.wasApproved()).isFalse();
        p.formalized(UUID.randomUUID(), TODAY, NOW);
        assertThat(p.getStatus()).isEqualTo(ProposalStatus.FORMALIZED);
        assertThatThrownBy(() -> p.expire(NOW)).isInstanceOf(ConflictException.class);
    }

    @Test
    void approvalIsSegregatedAndRequiredBeforeAcceptance() {
        Proposal p = proposal(ProposalStatus.PENDING_APPROVAL);
        assertThatThrownBy(() -> p.accept(evidence(), TODAY)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> p.approve(proposer, "SUPERVISOR", null, NOW)).isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("própria");
        UUID approver = UUID.randomUUID();
        p.approve(approver, "SUPERVISOR", "ok", NOW);
        assertThat(p.getStatus()).isEqualTo(ProposalStatus.APPROVED);
        p.accept(evidence(), TODAY);
        assertThat(p.wasApproved()).isTrue();
        assertThatThrownBy(() -> p.approve(approver, "SUPERVISOR", null, NOW)).isInstanceOf(ConflictException.class);
    }

    @Test
    void rejectionRequiresReasonAndExpirationFollowsValidity() {
        Proposal p = proposal(ProposalStatus.PENDING_APPROVAL);
        assertThatThrownBy(() -> p.reject(UUID.randomUUID(), "GESTOR", " ", NOW)).isInstanceOf(BusinessRuleException.class);
        p.reject(UUID.randomUUID(), "GESTOR", "Desconto acima do usual", NOW);
        assertThat(p.getStatus()).isEqualTo(ProposalStatus.REJECTED);

        Proposal expiring = proposal(ProposalStatus.DRAFT);
        assertThat(expiring.isExpiredOn(TODAY.plusDays(3))).isFalse();
        assertThat(expiring.isExpiredOn(TODAY.plusDays(4))).isTrue();
        assertThatThrownBy(() -> expiring.accept(evidence(), TODAY.plusDays(4))).isInstanceOf(ConflictException.class);
        expiring.expire(NOW);
        assertThat(expiring.getStatus()).isEqualTo(ProposalStatus.EXPIRED);
    }

    @Test
    void anonymizationErasesFreeTextAndNetworkData() {
        Proposal p = proposal(ProposalStatus.DRAFT);
        p.accept(new AcceptanceEvidence("PORTAL", NOW, "USER", proposer, null, "200.1.1.1", "Mozilla", UUID.randomUUID(),
                "k", "c".repeat(64), 10), TODAY);
        p.anonymize();
        assertThat(p.getAcceptance().getIp()).isNull();
        assertThat(p.getAcceptance().getUserAgent()).isNull();
        assertThat(p.getAcceptance().getTermSha256()).isEqualTo("c".repeat(64));
    }
}
