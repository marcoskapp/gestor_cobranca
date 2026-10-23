package br.com.gestorcobranca.legal.application.service;

import br.com.gestorcobranca.legal.domain.model.AuthorizationStatus;
import br.com.gestorcobranca.legal.domain.model.ClosureReason;
import br.com.gestorcobranca.legal.domain.model.CreditorAuthorization;
import br.com.gestorcobranca.legal.domain.model.LegalMatter;
import br.com.gestorcobranca.legal.domain.model.LegalRoute;
import br.com.gestorcobranca.legal.domain.model.StageRequirements;
import br.com.gestorcobranca.legal.domain.model.ViabilityAssessment;
import br.com.gestorcobranca.legal.domain.repository.CreditorAuthorizationRepository;
import br.com.gestorcobranca.legal.domain.repository.LawsuitRepository;
import br.com.gestorcobranca.legal.domain.repository.ViabilityAssessmentRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Reúne os fatos dos demais agregados que condicionam as transições de fase. */
@Component
class MatterRequirements {

    private final ViabilityAssessmentRepository assessments;
    private final CreditorAuthorizationRepository authorizations;
    private final LawsuitRepository lawsuits;

    MatterRequirements(ViabilityAssessmentRepository assessments, CreditorAuthorizationRepository authorizations,
                       LawsuitRepository lawsuits) {
        this.assessments = assessments;
        this.authorizations = authorizations;
        this.lawsuits = lawsuits;
    }

    StageRequirements of(LegalMatter matter, UUID agreementId, ClosureReason closureReason) {
        Optional<ViabilityAssessment> viability = assessments.findFirstByMatterIdOrderByCreatedAtDesc(matter.getId());
        return new StageRequirements(viability.isPresent(), viability.map(ViabilityAssessment::isViable).orElse(null),
                authorizedRoutes(matter.getId()), lawsuits.existsByMatterId(matter.getId()), agreementId, closureReason);
    }

    Set<LegalRoute> authorizedRoutes(UUID matterId) {
        return granted(matterId).map(CreditorAuthorization::getAuthorizedRoutes).orElse(Set.of());
    }

    Optional<CreditorAuthorization> granted(UUID matterId) {
        return authorizations.findFirstByMatterIdAndStatus(matterId, AuthorizationStatus.GRANTED);
    }
}
