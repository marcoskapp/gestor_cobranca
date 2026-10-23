package br.com.gestorcobranca.compliance.application.service;

import br.com.gestorcobranca.compliance.domain.model.ContactRestriction;
import br.com.gestorcobranca.compliance.domain.model.RestrictionType;
import br.com.gestorcobranca.compliance.domain.repository.ContactRestrictionRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.events.compliance.ContactRestrictionChanged;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.shared.security.CurrentActor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * Persistência das mudanças de restrição com os efeitos obrigatórios, na transação do caso de
 * uso: auditoria, evento de negócio e {@link ContactRestrictionChanged} para os demais módulos.
 * Comum ao cadastro manual, ao opt-out do provedor, à oposição do titular e ao fim de vigência.
 */
@Component
class RestrictionRecorder {

    private final ContactRestrictionRepository restrictions;
    private final AuditTrail auditTrail;
    private final BusinessEventLogger businessEvents;
    private final ApplicationEventPublisher events;
    private final CurrentActor currentActor;
    private final Clock clock;

    RestrictionRecorder(ContactRestrictionRepository restrictions, AuditTrail auditTrail,
                        BusinessEventLogger businessEvents, ApplicationEventPublisher events, CurrentActor currentActor,
                        Clock clock) {
        this.restrictions = restrictions;
        this.auditTrail = auditTrail;
        this.businessEvents = businessEvents;
        this.events = events;
        this.currentActor = currentActor;
        this.clock = clock;
    }

    ContactRestriction created(ContactRestriction restriction) {
        ContactRestriction saved = restrictions.save(restriction);
        auditTrail.record(AuditEntry.of("contact_restriction.created", "contact_restriction", saved.getId())
                .creditor(saved.getCreditorId())
                .reason(saved.getReason())
                .created("debtorId", saved.getDebtorId())
                .created("creditorId", saved.getCreditorId())
                .created("channel", saved.getChannel())
                .created("type", saved.getType())
                .created("contact", saved.getContactValueMasked())
                .created("source", saved.getSource())
                .created("startsAt", saved.getStartsAt())
                .created("endsAt", saved.getEndsAt())
                .meta("originRequestId", saved.getOriginRequestId()));
        businessEvents.log(BusinessEvent.of(saved.getType() == RestrictionType.OPT_OUT
                        ? BusinessEventTypes.CONTACT_OPT_OUT : BusinessEventTypes.CONTACT_RESTRICTED)
                .creditor(saved.getCreditorId()).debtor(saved.getDebtorId())
                .reference("contact_restriction", saved.getId())
                .channel(ComplianceEnums.name(saved.getChannel()))
                .attr("type", saved.getType())
                .attr("source", saved.getSource())
                .attr("temporary", saved.isTemporary())
                .attr("contactSpecific", saved.getContactValueHash() != null));
        publish(saved, true);
        return saved;
    }

    ContactRestriction revoked(ContactRestriction restriction) {
        ContactRestriction saved = restrictions.save(restriction);
        auditTrail.record(AuditEntry.of("contact_restriction.revoked", "contact_restriction", saved.getId())
                .creditor(saved.getCreditorId())
                .reason(saved.getRevokedReason())
                .change("status", "ACTIVE", saved.getStatus())
                .meta("debtorId", saved.getDebtorId()));
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.CONTACT_RESTRICTION_REVOKED)
                .creditor(saved.getCreditorId()).debtor(saved.getDebtorId())
                .reference("contact_restriction", saved.getId())
                .channel(ComplianceEnums.name(saved.getChannel()))
                .attr("type", saved.getType())
                .attr("activeDays", java.time.Duration.between(saved.getStartsAt(), saved.getRevokedAt()).toDays()));
        publish(saved, false);
        return saved;
    }

    /** Fim de vigência de restrição temporária: o contato volta a ser elegível. */
    ContactRestriction expired(ContactRestriction restriction, Instant now) {
        restriction.markExpiryNotified(now);
        ContactRestriction saved = restrictions.save(restriction);
        auditTrail.record(AuditEntry.of("contact_restriction.expired", "contact_restriction", saved.getId())
                .creditor(saved.getCreditorId())
                .meta("endsAt", saved.getEndsAt())
                .meta("debtorId", saved.getDebtorId()));
        publish(saved, false);
        return saved;
    }

    private void publish(ContactRestriction r, boolean restricted) {
        events.publishEvent(new ContactRestrictionChanged(Ids.newId(), currentActor.tenantId(), Instant.now(clock), r.getId(),
                r.getDebtorId(), r.getCreditorId(), ComplianceEnums.name(r.getChannel()), r.getType().name(), restricted));
    }
}
