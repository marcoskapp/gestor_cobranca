package br.com.gestorcobranca.negotiation.domain.model.agreement;

import br.com.gestorcobranca.negotiation.domain.model.agreement.Agreement.BucketPosting;
import br.com.gestorcobranca.negotiation.domain.model.agreement.Agreement.Receipt;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Agregado do acordo: estados (RB05), baixa de parcelas, distribuição entre componentes com
 * reconhecimento de encargos na medida do pagamento, estorno, quebra pela regra da política e
 * lançamentos da quitação.
 */
class AgreementTest {

    static final LocalDate D0 = LocalDate.of(2026, 11, 2);
    static final Instant T0 = Instant.parse("2026-11-02T13:00:00Z");
    static final UUID TITLE_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    static final UUID TITLE_B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    static Money m(String v) {
        return Money.of(v);
    }

    /**
     * Título A: principal 1000, juros 20 (desconto 10), multa 20, honorários 104 (reconhecidos antes: 0).
     * Título B: principal 500. Total líquido 1634; 3 parcelas: 634 + 500 + 500; quebra: 2 parcelas, 5 dias.
     */
    static Agreement agreement(int breakInstallments, int toleranceDays) {
        Map<LedgerPart, Money> grossA = new EnumMap<>(LedgerPart.class);
        grossA.put(LedgerPart.PRINCIPAL, m("1000.00"));
        grossA.put(LedgerPart.INTEREST, m("20.00"));
        grossA.put(LedgerPart.FINE, m("20.00"));
        grossA.put(LedgerPart.HONORARIUM, m("104.00"));
        Map<LedgerPart, Money> discountA = new EnumMap<>(LedgerPart.class);
        discountA.put(LedgerPart.INTEREST, m("10.00"));
        Map<LedgerPart, Money> grossB = new EnumMap<>(LedgerPart.class);
        grossB.put(LedgerPart.PRINCIPAL, m("500.00"));
        List<Agreement.TitleParts> parts = List.of(
                new Agreement.TitleParts(TITLE_A, grossA, discountA, Map.of()),
                new Agreement.TitleParts(TITLE_B, grossB, Map.of(), Map.of()));
        List<Agreement.ScheduleEntry> schedule = List.of(new Agreement.ScheduleEntry(1, D0, m("634.00")),
                new Agreement.ScheduleEntry(2, D0.plusMonths(1), m("500.00")),
                new Agreement.ScheduleEntry(3, D0.plusMonths(2), m("500.00")));
        return new Agreement(terms(m("1634.00"), breakInstallments, toleranceDays), schedule, parts);
    }

    static Agreement.Terms terms(Money total, int breakInstallments, int toleranceDays) {
        AcceptanceEvidence acceptance = new AcceptanceEvidence("PHONE", T0, "USER", UUID.randomUUID(), null, "10.0.0.1",
                "JUnit", UUID.randomUUID(), "tenants/x/agreement-terms/t.pdf", "a".repeat(64), 100);
        return new Agreement.Terms(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), null, null, UUID.randomUUID(), 1, null, D0, 1, new BigDecimal("25"), 60, "PHONE",
                AgreementOrigin.HUMAN, "USER", UUID.randomUUID(), "USER", UUID.randomUUID(), null, null, m("1644.00"),
                m("10.00"), total, Money.ZERO, total, m("634.00"), breakInstallments, toleranceDays, null, 0, total,
                acceptance, T0);
    }

    private static UUID installmentId(Agreement a, int number) {
        return a.getInstallments().get(number - 1).getId();
    }

    @Test
    void paymentsSettleInstallmentsInOrderAndMoveStatusFormalizedInPaymentSettled() {
        Agreement a = agreement(2, 5);
        assertThat(a.getStatus()).isEqualTo(AgreementStatus.FORMALIZED);
        assertThat(a.titleIds()).containsExactlyInAnyOrder(TITLE_A, TITLE_B);

        Receipt first = a.receive(Map.of(installmentId(a, 1), m("634.00")), Money.ZERO, D0, T0);
        assertThat(first.appliedTotal()).isEqualTo(m("634.00"));
        assertThat(first.statusChanges()).extracting(Agreement.StatusChange::current)
                .containsExactly(AgreementStatus.IN_PAYMENT);
        assertThat(a.getInstallments().getFirst().getStatus()).isEqualTo(InstallmentStatus.PAID);

        // valor sem parcela indicada vai às parcelas abertas em ordem; excedente não é aplicado
        Receipt rest = a.receive(Map.of(), m("1100.00"), D0, T0);
        assertThat(rest.appliedTotal()).isEqualTo(m("1000.00"));
        assertThat(rest.unapplied()).isEqualTo(m("100.00"));
        assertThat(rest.statusChanges()).extracting(Agreement.StatusChange::current)
                .containsExactly(AgreementStatus.SETTLED);
        assertThat(a.getPaidAmount()).isEqualTo(m("1634.00"));
    }

    @Test
    void partialPaymentAndUnknownInstallmentCascade() {
        Agreement a = agreement(2, 5);
        Receipt r = a.receive(Map.of(UUID.randomUUID(), m("100.00")), Money.ZERO, D0, T0);
        assertThat(r.applied()).containsOnlyKeys(installmentId(a, 1));
        assertThat(a.getInstallments().getFirst().getStatus()).isEqualTo(InstallmentStatus.PARTIALLY_PAID);
        // partial payment past the due date counts as overdue
        assertThat(a.refreshInstallments(D0.plusDays(1))).extracting(Agreement.InstallmentChange::current)
                .containsExactly(InstallmentStatus.OVERDUE);
    }

    @Test
    void allocationRecognizesChargesOnlyAsTheyArePaidAndSumsExactly() {
        Agreement a = agreement(2, 5);
        a.receive(Map.of(installmentId(a, 1), m("634.00")), Money.ZERO, D0, T0);
        List<BucketPosting> postings = a.allocate(m("634.00"));
        Money paid = postings.stream().map(BucketPosting::payment).reduce(Money.ZERO, Money::plus);
        assertThat(paid).isEqualTo(m("634.00"));
        // 634 de 1634 ≈ 38,8%: juros líquidos 10 → 3,88; multa 20 → 7,76; principal A ≈ 388
        BucketPosting interest = postings.stream().filter(p -> p.titleId().equals(TITLE_A) && p.part() == LedgerPart.INTEREST)
                .findFirst().orElseThrow();
        assertThat(interest.payment()).isEqualTo(interest.charge());
        assertThat(interest.payment()).isEqualTo(m("3.88"));
        postings.stream().filter(p -> p.part() == LedgerPart.PRINCIPAL)
                .forEach(p -> assertThat(p.charge()).isEqualTo(Money.ZERO));
        // o restante paga exatamente o saldo de cada componente
        a.receive(Map.of(), m("1000.00"), D0, T0);
        List<BucketPosting> rest = a.allocate(m("1000.00"));
        assertThat(rest.stream().map(BucketPosting::payment).reduce(Money.ZERO, Money::plus)).isEqualTo(m("1000.00"));
        a.getBuckets().forEach(b -> assertThat(b.getPaid()).isEqualTo(b.getPayable()));
        assertThatThrownBy(() -> a.allocate(m("0.01"))).isInstanceOf(ConflictException.class);
    }

    @Test
    void titleFullyPaidReceivesSettlementChargesAndDiscountInTheSamePosting() {
        Agreement a = agreement(2, 5);
        a.receive(Map.of(), m("1634.00"), D0, T0);
        List<BucketPosting> postings = a.allocate(m("1634.00"));
        BucketPosting interest = postings.stream().filter(p -> p.part() == LedgerPart.INTEREST).findFirst().orElseThrow();
        // juros líquidos pagos 10 (reconhecidos no pagamento); bruto 20 → reconhecer mais 10 e descontar 10
        assertThat(interest.payment()).isEqualTo(m("10.00"));
        assertThat(interest.charge()).isEqualTo(m("10.00"));
        assertThat(interest.settlementCharge()).isEqualTo(m("10.00"));
        assertThat(interest.discount()).isEqualTo(m("10.00"));
        // título B sem desconto: só pagamento
        assertThat(postings.stream().filter(p -> p.titleId().equals(TITLE_B))).singleElement()
                .satisfies(p -> assertThat(p.discount()).isEqualTo(Money.ZERO));
        a.getBuckets().forEach(b -> assertThat(b.getRecognized()).isEqualTo(b.getGross()));
        // estorno e novo pagamento: a quitação se repete sem novo reconhecimento (lançamento idempotente)
        Map<UUID, Money> all = new LinkedHashMap<>();
        a.getInstallments().forEach(i -> all.put(i.getId(), i.getAmount()));
        a.revert(all, D0, T0);
        assertThat(a.getStatus()).isEqualTo(AgreementStatus.IN_PAYMENT);
        a.deallocate(postings, true);
        a.receive(all, Money.ZERO, D0, T0);
        assertThat(a.getStatus()).isEqualTo(AgreementStatus.SETTLED);
        List<BucketPosting> again = a.allocate(m("1634.00"));
        assertThat(again).allSatisfy(p -> assertThat(p.settlementCharge()).isEqualTo(Money.ZERO));
        assertThat(again.stream().filter(p -> p.part() == LedgerPart.INTEREST).findFirst().orElseThrow().discount())
                .isEqualTo(m("10.00"));
    }

    @Test
    void reversalBeforeLedgerPostingUndoesPlannedRecognition() {
        Agreement a = agreement(2, 5);
        Receipt r = a.receive(Map.of(installmentId(a, 1), m("634.00")), Money.ZERO, D0, T0);
        List<BucketPosting> postings = a.allocate(m("634.00"));
        a.revert(r.applied(), D0, T0);
        a.deallocate(postings, false);
        a.getBuckets().forEach(b -> {
            assertThat(b.getPaid()).isEqualTo(Money.ZERO);
            assertThat(b.getRecognized()).isEqualTo(b.getPart() == LedgerPart.PRINCIPAL ? b.getGross() : Money.ZERO);
        });
        assertThat(a.getInstallments().getFirst().getStatus()).isEqualTo(InstallmentStatus.OPEN);
        // já lançado no razão: o reconhecimento permanece
        a.receive(Map.of(installmentId(a, 1), m("634.00")), Money.ZERO, D0, T0);
        List<BucketPosting> again = a.allocate(m("634.00"));
        a.revert(Map.of(installmentId(a, 1), m("634.00")), D0, T0);
        a.deallocate(again, true);
        assertThat(a.getBuckets().stream().filter(b -> b.getPart() == LedgerPart.INTEREST).findFirst().orElseThrow()
                .getRecognized()).isEqualTo(m("3.88"));
    }

    @Test
    void isolatedDelayWithinToleranceDoesNotBreak() {
        Agreement a = agreement(2, 5);
        a.receive(Map.of(installmentId(a, 1), m("634.00")), Money.ZERO, D0, T0);
        LocalDate secondDue = D0.plusMonths(1);
        assertThat(a.breakRuleMet(secondDue.plusDays(5))).isFalse();
        // uma parcela além da tolerância, mas a regra exige duas
        assertThat(a.breakRuleMet(secondDue.plusDays(6))).isFalse();
        LocalDate thirdDue = D0.plusMonths(2);
        assertThat(a.breakRuleMet(thirdDue.plusDays(5))).isFalse();
        assertThat(a.breakRuleMet(thirdDue.plusDays(6))).isTrue();
    }

    @Test
    void breakCancelsOpenInstallmentsAndReleasesTitles() {
        Agreement a = agreement(1, 0);
        assertThat(a.breakRuleMet(D0)).isFalse();
        assertThat(a.breakRuleMet(D0.plusDays(1))).isTrue();
        Agreement.Ending ending = a.breakAgreement("regra", D0.plusDays(1), T0);
        assertThat(ending.change().current()).isEqualTo(AgreementStatus.BROKEN);
        assertThat(a.getInstallments()).allSatisfy(i -> assertThat(i.getStatus()).isEqualTo(InstallmentStatus.CANCELLED));
        assertThat(ending.installmentChanges()).hasSize(3);
        assertThat(a.breakRuleMet(D0.plusDays(30))).isFalse();
        assertThatThrownBy(() -> a.cancel("x", D0, T0)).isInstanceOf(ConflictException.class);
        // quebrado pode ser renegociado; o histórico é preservado
        UUID next = UUID.randomUUID();
        Agreement.Ending renegotiated = a.renegotiatedBy(next, D0.plusDays(2), T0);
        assertThat(renegotiated.change().previous()).isEqualTo(AgreementStatus.BROKEN);
        assertThat(a.getReplacedByAgreementId()).isEqualTo(next);
        assertThatThrownBy(() -> a.renegotiatedBy(UUID.randomUUID(), D0, T0)).isInstanceOf(ConflictException.class);
    }

    @Test
    void paymentToEndedAgreementIsRejectedByTheAggregate() {
        Agreement a = agreement(1, 0);
        a.cancel("Pedido do credor", D0, T0);
        assertThat(a.getStatus()).isEqualTo(AgreementStatus.CANCELLED);
        assertThatThrownBy(() -> a.receive(Map.of(), m("1.00"), D0, T0)).isInstanceOf(ConflictException.class);
    }

    @Test
    void compositionMustMatchTheSchedule() {
        Map<LedgerPart, Money> gross = new EnumMap<>(LedgerPart.class);
        gross.put(LedgerPart.PRINCIPAL, m("100.00"));
        assertThatThrownBy(() -> new Agreement(terms(m("120.00"), 1, 0),
                List.of(new Agreement.ScheduleEntry(1, D0, m("120.00"))),
                List.of(new Agreement.TitleParts(TITLE_A, gross, Map.of(), Map.of()))))
                .hasMessageContaining("composição");
    }
}
