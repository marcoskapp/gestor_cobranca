package br.com.gestorcobranca.negotiation.domain.repository;

import br.com.gestorcobranca.negotiation.domain.model.agreement.BreakSuspension;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Suspensões da quebra do acordo por pagamento em revisão (RLS do tenant corrente). */
public interface BreakSuspensionRepository {

    BreakSuspension save(BreakSuspension suspension);

    /** A suspensão em vigor do acordo (no máximo uma — índice único parcial). */
    Optional<BreakSuspension> findActive(UUID agreementId);

    /** A suspensão mais recente do acordo (em vigor ou encerrada). */
    Optional<BreakSuspension> findLatest(UUID agreementId);

    /** A mais recente de cada acordo informado (acordos sem suspensão não vêm). */
    List<BreakSuspension> findLatestByAgreements(Collection<UUID> agreementIds);

    /** A parcela já teve suspensão encerrada por recusa ou prazo (não suspende de novo). */
    boolean existsBlockingEnd(UUID triggerInstallmentId);

    /** Acordos com suspensão em vigor, ids após {@code afterId}, em ordem. */
    List<UUID> findActiveAgreementIds(UUID afterId, int limit);
}
