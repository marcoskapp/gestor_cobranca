package br.com.gestorcobranca.debt.domain.model;

import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Money;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DebtTitleTest {

    static DebtTitle title(String principal, LocalDate dueDate) {
        return new DebtTitle(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, " CTR-1/01 ", 1, dueDate,
                Money.of(principal), UUID.randomUUID());
    }

    @Test
    void manualLifecycleFollowsAllowedTransitions() {
        DebtTitle t = title("100.00", LocalDate.of(2026, 1, 10));
        assertThat(t.getExternalId()).isEqualTo("CTR-1/01");
        assertThat(t.getStatus()).isEqualTo(TitleStatus.OPEN);

        assertThat(t.suspend("pedido do credor")).isEqualTo(TitleStatus.OPEN);
        assertThat(t.getStatusReason()).isEqualTo("pedido do credor");
        assertThat(t.reactivate()).isEqualTo(TitleStatus.SUSPENDED);
        assertThat(t.getStatusReason()).isNull();
        t.returnToCreditor("fim da vigência");
        assertThat(t.getStatus()).isEqualTo(TitleStatus.RETURNED);

        assertThatThrownBy(t::reactivate).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> t.cancel("x")).isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", "title.invalid_transition");
    }

    @Test
    void finalStatusesHaveNoTransitions() {
        assertThat(TitleStatus.CANCELLED.allowedTransitions()).isEmpty();
        assertThat(TitleStatus.RETURNED.allowedTransitions()).isEmpty();
        assertThat(TitleStatus.SETTLED.allowedTransitions()).containsExactly(TitleStatus.OPEN);
    }

    @Test
    void settledCannotBeSuspendedOrCancelledManually() {
        DebtTitle t = title("50.00", LocalDate.of(2026, 1, 10));
        t.recordPosting(LedgerEntryType.PAYMENT, Money.ZERO, Money.of("50.00"), Money.ZERO, null);
        assertThat(t.getStatus()).isEqualTo(TitleStatus.SETTLED);
        assertThatThrownBy(() -> t.suspend("x")).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> t.cancel("x")).isInstanceOf(ConflictException.class);
    }

    /** H07 2b (RS-98): o lote da carga só fica no título quitado e sai quando o título reabre. */
    @Test
    void importSettlementIsKeptOnlyWhileSettled() {
        DebtTitle t = title("50.00", LocalDate.of(2026, 1, 10));
        UUID batch = UUID.randomUUID();
        java.time.Instant at = java.time.Instant.parse("2026-11-26T12:00:00Z");
        assertThat(t.markSettledByImport(batch, at)).isFalse();
        assertThat(t.getSettledByImportBatchId()).isNull();

        t.recordPosting(LedgerEntryType.ADJUSTMENT, Money.ZERO, Money.ZERO, Money.ZERO, null);
        assertThat(t.getStatus()).isEqualTo(TitleStatus.SETTLED);
        assertThat(t.markSettledByImport(batch, at)).isTrue();
        assertThat(t.getSettledByImportBatchId()).isEqualTo(batch);
        assertThat(t.getSettledByImportAt()).isEqualTo(at);
        assertThatThrownBy(() -> t.markSettledByImport(null, at)).isInstanceOf(BusinessRuleException.class);

        t.recordPosting(LedgerEntryType.REVERSAL, Money.of("10.00"), Money.ZERO, Money.ZERO, null);
        assertThat(t.getStatus()).isEqualTo(TitleStatus.OPEN);
        assertThat(t.getSettledByImportBatchId()).isNull();
        assertThat(t.getSettledByImportAt()).isNull();
    }

    /** H07 2b: suspensão pela carga com motivo codificado e lote; a reativação limpa os dois. */
    @Test
    void creditorReportedSuspensionIsStructuredAndClearedOnReactivation() {
        DebtTitle t = title("100.00", LocalDate.of(2026, 1, 10));
        UUID batch = UUID.randomUUID();
        java.time.Instant at = java.time.Instant.parse("2026-11-26T12:00:00Z");
        assertThatThrownBy(() -> t.suspendReportedByCreditor(TitleSuspensionReason.CREDITOR_REPORTED_PAID, "motivo",
                null, at)).isInstanceOf(BusinessRuleException.class);
        assertThat(t.suspendReportedByCreditor(TitleSuspensionReason.CREDITOR_REPORTED_PAID,
                "Credor informou pagamento; aguardando conciliação (carga 1)", batch, at)).isEqualTo(TitleStatus.OPEN);
        assertThat(t.getStatus()).isEqualTo(TitleStatus.SUSPENDED);
        assertThat(t.getSuspensionReasonCode()).isEqualTo(TitleSuspensionReason.CREDITOR_REPORTED_PAID);
        assertThat(t.getSuspensionImportBatchId()).isEqualTo(batch);
        t.reactivate();
        assertThat(t.getSuspensionReasonCode()).isNull();
        assertThat(t.getSuspensionImportBatchId()).isNull();
        // a suspensão comum não leva motivo codificado
        t.suspend("pedido do credor");
        assertThat(t.getSuspensionReasonCode()).isNull();
    }

    @Test
    void manualChangesRequireReason() {
        DebtTitle t = title("100.00", LocalDate.of(2026, 1, 10));
        assertThatThrownBy(() -> t.suspend(" ")).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> t.cancel(null)).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> t.returnToCreditor("")).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void closedTitlesRejectPostingsButAcceptReversals() {
        DebtTitle t = title("100.00", LocalDate.of(2026, 1, 10));
        t.cancel("lançamento indevido");
        assertThatThrownBy(() -> t.requireAcceptsPosting(LedgerEntryType.PAYMENT))
                .isInstanceOf(ConflictException.class).hasFieldOrPropertyWithValue("code", "title.not_accepting_postings");
        t.requireAcceptsPosting(LedgerEntryType.REVERSAL);
    }

    @Test
    void daysOverdueOnlyForTitlesInCollection() {
        DebtTitle t = title("100.00", LocalDate.of(2026, 1, 10));
        assertThat(t.daysOverdue(LocalDate.of(2026, 1, 10))).isZero();
        assertThat(t.daysOverdue(LocalDate.of(2026, 1, 5))).isZero();
        assertThat(t.daysOverdue(LocalDate.of(2026, 2, 9))).isEqualTo(30);
        t.suspend("análise");
        assertThat(t.daysOverdue(LocalDate.of(2026, 2, 9))).isEqualTo(30);
        t.cancel("indevido");
        assertThat(t.daysOverdue(LocalDate.of(2026, 2, 9))).isZero();
    }

    @Test
    void disputeLinkIsIdempotentAndOnlyClearedByTheSameDispute() {
        DebtTitle t = title("100.00", LocalDate.of(2026, 1, 10));
        UUID dispute = UUID.randomUUID();
        assertThat(t.markDisputed(dispute, true)).isTrue();
        assertThat(t.markDisputed(dispute, true)).isFalse();
        assertThat(t.clearDispute(UUID.randomUUID())).isFalse();
        assertThat(t.isDisputeBlocked()).isTrue();
        assertThat(t.clearDispute(dispute)).isTrue();
        assertThat(t.isDisputeBlocked()).isFalse();
        assertThat(t.getDisputeId()).isNull();
    }

    @Test
    void transferRequiresDifferentPortfolioAndOpenTitle() {
        DebtTitle t = title("100.00", LocalDate.of(2026, 1, 10));
        assertThatThrownBy(() -> t.transferTo(t.getPortfolioId())).isInstanceOf(BusinessRuleException.class);
        UUID target = UUID.randomUUID();
        t.transferTo(target);
        assertThat(t.getPortfolioId()).isEqualTo(target);
        t.returnToCreditor("devolução");
        assertThatThrownBy(() -> t.transferTo(UUID.randomUUID())).isInstanceOf(ConflictException.class);
    }

    @Test
    void rejectsInvalidData() {
        assertThatThrownBy(() -> title("0", LocalDate.now())).isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", "title.principal_invalid");
        assertThatThrownBy(() -> new DebtTitle(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, " ", null,
                LocalDate.now(), Money.of(1), null)).hasFieldOrPropertyWithValue("code", "title.external_id_required");
        assertThatThrownBy(() -> new DebtTitle(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, "X", 0,
                LocalDate.now(), Money.of(1), null)).hasFieldOrPropertyWithValue("code", "title.installment_invalid");
    }

    /** Decisão 1603: estorno tardio em título quitado de titular eliminado deixa o título quitado, com o saldo. */
    @Test
    void reopenedTitleOfErasedHolderStaysSettled() {
        DebtTitle t = title("50.00", LocalDate.of(2026, 1, 10));
        assertThatThrownBy(t::keepSettledAfterErasure).hasFieldOrPropertyWithValue("code", "title.not_reopened");
        t.recordPosting(LedgerEntryType.PAYMENT, Money.ZERO, Money.of("50.00"), Money.ZERO, null);
        t.recordPosting(LedgerEntryType.REVERSAL, Money.of("50.00"), Money.ZERO, Money.ZERO, null);
        assertThat(t.getStatus()).isEqualTo(TitleStatus.OPEN);
        t.keepSettledAfterErasure();
        assertThat(t.getStatus()).isEqualTo(TitleStatus.SETTLED);
        assertThat(t.getOpenBalance()).isEqualTo(Money.of("50.00"));
        assertThat(t.getStatusReason()).isEqualTo(DebtTitle.ERASED_HOLDER_REASON);
    }

    @Test
    void reservedPrefixIsRejectedAndAnonymizationHappensOnce() {
        assertThatThrownBy(() -> new DebtTitle(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, " Anon:1 ", 1,
                LocalDate.of(2026, 1, 10), Money.of("10.00"), UUID.randomUUID()))
                .hasFieldOrPropertyWithValue("code", "title.external_id_reserved");
        DebtTitle t = title("10.00", LocalDate.of(2026, 1, 10));
        String anon = "anon:" + "a".repeat(64);
        assertThatThrownBy(() -> t.anonymizeExternalId("anon:xyz", "CTR-1/01"))
                .hasFieldOrPropertyWithValue("code", "title.external_id_anonymized_invalid");
        assertThat(t.anonymizeExternalId(anon, "***982247**-001")).isTrue();
        assertThat(t.getExternalId()).isEqualTo(anon);
        assertThat(t.displayExternalId()).isEqualTo("***982247**-001");
        assertThat(t.anonymizeExternalId("anon:" + "b".repeat(64), "outro")).isFalse();
        assertThat(t.getExternalId()).isEqualTo(anon);
    }

    @Test
    void controlCharacterInTheIdentifierIsRejectedAndContractAnonymizesOnce() {
        assertThatThrownBy(() -> new DebtTitle(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, "\u0001", 1,
                LocalDate.of(2026, 1, 10), Money.of("10.00"), UUID.randomUUID()))
                .hasFieldOrPropertyWithValue("code", "title.external_id_invalid");
        OriginContract c = new OriginContract(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "C-1", null, null,
                null);
        assertThat(c.anonymizeExternalId("anon:" + "c".repeat(64), "C-1")).isTrue();
        assertThat(c.displayExternalId()).isEqualTo("C-1");
        assertThat(c.anonymizeExternalId("anon:" + "d".repeat(64), "C-1")).isFalse();
    }
}
