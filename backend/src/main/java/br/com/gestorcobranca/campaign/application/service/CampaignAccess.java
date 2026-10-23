package br.com.gestorcobranca.campaign.application.service;

import br.com.gestorcobranca.campaign.domain.model.Campaign;
import br.com.gestorcobranca.campaign.domain.model.Enrollment;
import br.com.gestorcobranca.campaign.domain.model.Segment;
import br.com.gestorcobranca.campaign.domain.repository.CampaignRepository;
import br.com.gestorcobranca.campaign.domain.repository.EnrollmentRepository;
import br.com.gestorcobranca.campaign.domain.repository.SegmentRepository;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CurrentActor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Escopo por credor nos recursos do módulo: leitura fora do escopo → 404 (com registro no log de
 * acesso); comando → 403. Segmentos da assessoria (sem credor) exigem todos os credores.
 */
@Component
class CampaignAccess {

    private final SegmentRepository segments;
    private final CampaignRepository campaigns;
    private final EnrollmentRepository enrollments;
    private final AccessScope accessScope;
    private final CurrentActor currentActor;

    CampaignAccess(SegmentRepository segments, CampaignRepository campaigns, EnrollmentRepository enrollments,
                   AccessScope accessScope, CurrentActor currentActor) {
        this.segments = segments;
        this.campaigns = campaigns;
        this.enrollments = enrollments;
        this.accessScope = accessScope;
        this.currentActor = currentActor;
    }

    Actor actor() {
        return currentActor.get();
    }

    Segment visibleSegment(UUID segmentId) {
        Segment s = segments.findById(segmentId).orElseThrow(() -> ResourceNotFoundException.of("segment", segmentId));
        if (s.getCreditorId() == null) {
            // sem credor: visível só a quem tem todos os credores
            accessScope.requireVisibleInAny("segment", segmentId, java.util.List.of());
        } else {
            accessScope.requireVisible("segment", segmentId, s.getCreditorId());
        }
        return s;
    }

    Segment segmentForCommand(UUID segmentId) {
        Segment s = visibleSegment(segmentId);
        requireCreditorOrAll(s.getCreditorId(), "segment");
        return s;
    }

    void requireCreditorOrAll(UUID creditorId, String resource) {
        if (creditorId == null) {
            accessScope.requireAllCreditors(resource);
        } else {
            accessScope.requireCreditor(creditorId);
        }
    }

    Campaign visibleCampaign(UUID campaignId) {
        Campaign c = campaigns.findById(campaignId).orElseThrow(() -> ResourceNotFoundException.of("campaign", campaignId));
        accessScope.requireVisible("campaign", campaignId, c.getCreditorId());
        return c;
    }

    Campaign campaignForCommand(UUID campaignId) {
        Campaign c = campaigns.findByIdForUpdate(campaignId)
                .orElseThrow(() -> ResourceNotFoundException.of("campaign", campaignId));
        accessScope.requireVisible("campaign", campaignId, c.getCreditorId());
        accessScope.requireCreditor(c.getCreditorId());
        return c;
    }

    Enrollment visibleEnrollment(UUID enrollmentId) {
        Enrollment e = enrollments.findById(enrollmentId)
                .orElseThrow(() -> ResourceNotFoundException.of("campaign_enrollment", enrollmentId));
        accessScope.requireVisible("campaign_enrollment", enrollmentId, e.getCreditorId());
        return e;
    }

    Enrollment enrollmentForCommand(UUID enrollmentId) {
        Enrollment e = enrollments.findByIdForUpdate(enrollmentId)
                .orElseThrow(() -> ResourceNotFoundException.of("campaign_enrollment", enrollmentId));
        accessScope.requireVisible("campaign_enrollment", enrollmentId, e.getCreditorId());
        accessScope.requireCreditor(e.getCreditorId());
        return e;
    }

    AccessScope scope() {
        return accessScope;
    }
}
