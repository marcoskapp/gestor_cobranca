package br.com.gestorcobranca.campaign.application.service;

import br.com.gestorcobranca.campaign.application.CampaignProperties;
import br.com.gestorcobranca.campaign.domain.model.AudienceSnapshot;
import br.com.gestorcobranca.campaign.domain.model.Campaign;
import br.com.gestorcobranca.campaign.domain.model.CampaignStatus;
import br.com.gestorcobranca.campaign.domain.model.EndReason;
import br.com.gestorcobranca.campaign.domain.model.Enrollment;
import br.com.gestorcobranca.campaign.domain.model.EnrollmentStatus;
import br.com.gestorcobranca.campaign.domain.model.PauseReason;
import br.com.gestorcobranca.campaign.domain.repository.CampaignRepository;
import br.com.gestorcobranca.campaign.domain.repository.EnrollmentRepository;
import br.com.gestorcobranca.campaign.domain.service.PauseRules;
import br.com.gestorcobranca.shared.jobs.JobQueue;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Manutenção periódica por assessoria (chamada pelos agendamentos, já no tenant e em transação):
 * varredura de inscrição das campanhas ativas, retomadas programadas (pausa por pagamento), fim do
 * horizonte do grupo de controle e reagendamento de etapas ativas atrasadas sem job (retomada após
 * queda de instância ou reativação da campanha), com {@code SKIP LOCKED}.
 */
@Component
class CampaignMaintenance {

    private final CampaignRepository campaigns;
    private final EnrollmentRepository enrollments;
    private final AudienceProjection projection;
    private final EnrollmentLifecycle lifecycle;
    private final JobQueue jobQueue;
    private final CampaignProperties properties;
    private final Clock clock;

    CampaignMaintenance(CampaignRepository campaigns, EnrollmentRepository enrollments, AudienceProjection projection,
                        EnrollmentLifecycle lifecycle, JobQueue jobQueue, CampaignProperties properties, Clock clock) {
        this.campaigns = campaigns;
        this.enrollments = enrollments;
        this.projection = projection;
        this.lifecycle = lifecycle;
        this.jobQueue = jobQueue;
        this.properties = properties;
        this.clock = clock;
    }

    /** Enfileira a inscrição de novos casos de cada campanha ativa. @return campanhas varridas */
    int sweep() {
        List<Campaign> active = campaigns.findByStatus(CampaignStatus.ACTIVE);
        for (Campaign c : active) {
            jobQueue.enqueue(EnrollJob.TYPE, new EnrollJob.Payload(c.getId(), null), null, EnrollJob.dedupKey(c.getId(), null));
            reconcile(c.getId());
        }
        return active.size();
    }

    /** Reagenda etapas ativas atrasadas além da tolerância (o job da etapa se perdeu ou a campanha foi reativada). */
    int reconcile(UUID campaignId) {
        Instant before = Instant.now(clock).minus(properties.reconcileGrace());
        List<UUID> ids = enrollments.lockOverdueActiveIds(campaignId, before, properties.maintenanceBatch());
        for (UUID id : ids) {
            enrollments.findById(id).ifPresent(lifecycle::schedule);
        }
        return ids.size();
    }

    /** Reagenda todas as etapas ativas da campanha (reativação), sem tolerância. */
    int rescheduleAll(UUID campaignId) {
        int total = 0;
        UUID after = null;
        List<UUID> ids;
        do {
            ids = enrollments.findIdsByCampaignAndStatus(campaignId, EnumSet.of(EnrollmentStatus.ACTIVE), after,
                    properties.maintenanceBatch());
            for (UUID id : ids) {
                enrollments.findById(id).ifPresent(lifecycle::schedule);
            }
            total += ids.size();
            after = ids.isEmpty() ? after : ids.getLast();
        } while (ids.size() == properties.maintenanceBatch());
        return total;
    }

    /** Retomadas programadas (pausa por pagamento confirmado). @return inscrições avaliadas */
    int resumeDue() {
        Instant now = Instant.now(clock);
        List<UUID> ids = enrollments.findIdsDueToResume(now, properties.maintenanceBatch());
        for (UUID id : ids) {
            Enrollment e = enrollments.findByIdForUpdate(id).orElseThrow();
            if (e.getStatus() != EnrollmentStatus.PAUSED || e.getResumeAt() == null || e.getResumeAt().isAfter(now)) {
                continue;
            }
            Campaign c = campaigns.findById(e.getCampaignId()).orElseThrow();
            AudienceSnapshot snapshot = projection.refresh(e.getCaseId(), "scheduled_resume");
            if (!snapshot.active()) {
                lifecycle.end(e, c, EndReason.CASE_CLOSED);
                continue;
            }
            Optional<PauseReason> blocking = PauseRules.blocking(snapshot, c.getPurpose());
            if (blocking.isPresent()) {
                e.changePauseReason(blocking.get());
                enrollments.save(e);
            } else {
                lifecycle.resume(e, "scheduled");
            }
        }
        return ids.size();
    }

    /** Controle cujo horizonte de observação terminou. */
    int finishHoldouts() {
        List<UUID> ids = enrollments.findHoldoutIdsFinished(Instant.now(clock), properties.maintenanceBatch());
        for (UUID id : ids) {
            Enrollment e = enrollments.findByIdForUpdate(id).orElseThrow();
            Campaign c = campaigns.findById(e.getCampaignId()).orElseThrow();
            lifecycle.end(e, c, EndReason.HOLDOUT_FINISHED);
        }
        return ids.size();
    }

    /** Encerra um lote de inscrições em andamento de campanha encerrada. @return encerradas */
    int finishEnrollments(Campaign campaign, UUID afterId, int limit) {
        List<UUID> ids = enrollments.findIdsByCampaignAndStatus(campaign.getId(),
                EnumSet.of(EnrollmentStatus.ACTIVE, EnrollmentStatus.PAUSED, EnrollmentStatus.FAILED), afterId, limit);
        for (UUID id : ids) {
            Enrollment e = enrollments.findByIdForUpdate(id).orElseThrow();
            if (e.isRunning()) {
                lifecycle.end(e, campaign, EndReason.CAMPAIGN_FINISHED);
            }
        }
        if (ids.size() == limit) {
            jobQueue.enqueue(FinishCampaignJob.TYPE, new FinishCampaignJob.Payload(campaign.getId(), ids.getLast()), null,
                    "campaign:" + campaign.getId() + ":finish:" + ids.getLast());
        }
        return ids.size();
    }
}
