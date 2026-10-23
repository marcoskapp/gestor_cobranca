package br.com.gestorcobranca.settlement.application.port.out;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Fatos de outros módulos lidos pelas portas públicas permitidas (payment, negotiation, organization).
 * A leitura é feita como processo do sistema no tenant corrente: a prestação de contas precisa da
 * situação de conciliação e da distribuição do pagamento independentemente das permissões de
 * pagamento de quem calcula — o escopo por credor é aplicado pelo próprio settlement antes da chamada.
 */
public interface SettlementSources {

    /** Pagamento dono da alocação (o {@code paymentId} dos eventos). */
    Optional<PaymentFacts> paymentOfAllocation(UUID allocationId);

    /** Distribuição do pagamento aplicado ao acordo (vazio se o negotiation não registrou). */
    Optional<AgreementPaymentFacts> agreementPayment(UUID agreementId, UUID allocationId);

    /** Número de parcelas do acordo (1 = à vista); vazio se o acordo não existe. */
    Optional<Integer> agreementInstallmentCount(UUID agreementId);

    /** Divergências de conciliação em aberto do credor. */
    List<OpenDivergence> openDivergences(UUID creditorId);

    Optional<UUID> contractOfPortfolio(UUID portfolioId);

    List<ContractFacts> contracts(UUID creditorId);

    /** Identificação do credor para o demonstrativo (razão social e CNPJ). */
    Optional<CreditorFacts> creditor(UUID creditorId);

    record CreditorFacts(UUID id, String legalName, String document) {
    }

    /**
     * @param status         INFORMED, CONFIRMED, RECONCILED, REJECTED ou REVERSED
     * @param externalOrigin AGENCY_ACCOUNT, CREDITOR_DIRECT, CREDITOR_REPORT, OTHER (pagamento externo) ou nulo
     */
    record PaymentFacts(UUID paymentId, String status, String source, String method, String externalOrigin,
                        Instant paidAt, Instant reconciledAt) {

        public boolean reconciled() {
            return "RECONCILED".equals(status) || reconciledAt != null;
        }

        /** Pagamento recebido diretamente pelo credor (informado pela origem do pagamento externo). */
        public boolean receivedByCreditor() {
            return "CREDITOR_DIRECT".equals(externalOrigin) || "CREDITOR_REPORT".equals(externalOrigin);
        }
    }

    record InstallmentShare(UUID installmentId, int number, BigDecimal amount) {
    }

    record ComponentShare(UUID titleId, String titleReference, String component, BigDecimal amount) {
    }

    record AgreementPaymentFacts(UUID agreementId, UUID creditorId, UUID debtorId, UUID portfolioId, String status,
                                 BigDecimal appliedAmount, Instant paidAt, String source, String method,
                                 int installmentCount, long maxDaysOverdue, int renegotiationNumber,
                                 List<InstallmentShare> installments, List<ComponentShare> components) {
    }

    record OpenDivergence(UUID id, String type, UUID paymentId, BigDecimal amount, LocalDate occurredOn) {
    }

    record ContractFacts(UUID id, UUID creditorId, String number, LocalDate startDate, LocalDate endDate,
                         int settlementDay, String status) {

        /** Vigência do contrato (o encerrado vale até a data de encerramento). */
        public boolean effectiveOn(LocalDate date) {
            return !date.isBefore(startDate) && (endDate == null || !date.isAfter(endDate));
        }
    }
}
