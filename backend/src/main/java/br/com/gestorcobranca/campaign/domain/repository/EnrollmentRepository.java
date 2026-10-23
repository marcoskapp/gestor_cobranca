package br.com.gestorcobranca.campaign.domain.repository;

import br.com.gestorcobranca.campaign.domain.model.Enrollment;
import br.com.gestorcobranca.campaign.domain.model.EnrollmentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EnrollmentRepository {

    Enrollment save(Enrollment enrollment);

    Enrollment saveAndFlush(Enrollment enrollment);

    Optional<Enrollment> findById(UUID id);

    /** {@code SELECT … FOR UPDATE}: serializa o job de etapa com pausas, respostas e comandos. */
    Optional<Enrollment> findByIdForUpdate(UUID id);

    /** Inscrições em andamento do devedor num grupo de exclusividade (com lock), para a concorrência. */
    List<Enrollment> findRunningInGroupForUpdate(UUID debtorId, String exclusivityGroup);

    /** Inscrições em andamento (ACTIVE, PAUSED, FAILED) do devedor, com lock. */
    List<Enrollment> findRunningByDebtorForUpdate(UUID debtorId);

    /** Inscrições em andamento do caso, com lock. */
    List<Enrollment> findRunningByCaseForUpdate(UUID caseId);

    /** Inscrições do devedor no credor que podem observar um resultado (em andamento, controle ou na janela). */
    List<Enrollment> findObservingByDebtorAndCreditor(UUID debtorId, UUID creditorId, Instant at);

    /** Última inscrição da campanha para o caso (reinscrição). */
    Optional<Enrollment> findLatestByCampaignAndCase(UUID campaignId, UUID caseId);

    /** Inscrições pausadas com retomada programada vencida. */
    List<UUID> findIdsDueToResume(Instant now, int limit);

    /** Controle com horizonte de observação encerrado. */
    List<UUID> findHoldoutIdsFinished(Instant now, int limit);

    /** Ativas cuja etapa está atrasada além da tolerância (job perdido ou campanha reativada), com SKIP LOCKED. */
    List<UUID> lockOverdueActiveIds(UUID campaignId, Instant before, int limit);

    /** Inscrições da campanha nas situações informadas (keyset por id). */
    List<UUID> findIdsByCampaignAndStatus(UUID campaignId, Collection<EnrollmentStatus> statuses, UUID afterId, int limit);

    Page<Enrollment> search(UUID campaignId, EnrollmentStatus status, UUID debtorId, UUID caseId, Pageable pageable);

    List<Enrollment> findByCaseId(UUID caseId);
}
