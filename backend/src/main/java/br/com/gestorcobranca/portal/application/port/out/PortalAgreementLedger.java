package br.com.gestorcobranca.portal.application.port.out;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Acordos fechados no portal (tabela {@code portal.portal_agreement}, append-only exceto o primeiro
 * pagamento): base das etapas aceite → pagamento do funil e da marcação "fechado no portal".
 */
public interface PortalAgreementLedger {

    void record(Entry entry);

    Optional<Entry> find(UUID agreementId);

    /** @return verdadeiro se este foi o primeiro pagamento confirmado do acordo */
    boolean markFirstPaid(UUID agreementId, Instant paidAt);

    Set<UUID> agreementIdsOf(UUID debtorId, UUID creditorId);

    record Entry(UUID agreementId, UUID sessionId, UUID configurationId, UUID creditorId, UUID portfolioId,
                 UUID debtorId, UUID proposalId, int installments, BigDecimal totalAmount, Instant formalizedAt,
                 Instant firstPaidAt) {
    }
}
