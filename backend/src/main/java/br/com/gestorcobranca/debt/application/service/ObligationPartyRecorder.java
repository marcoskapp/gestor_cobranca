package br.com.gestorcobranca.debt.application.service;

import br.com.gestorcobranca.debt.domain.model.Debtor;
import br.com.gestorcobranca.debt.domain.model.ObligationParty;
import br.com.gestorcobranca.debt.domain.model.ObligationRole;
import br.com.gestorcobranca.debt.domain.model.OriginContract;
import br.com.gestorcobranca.debt.domain.repository.ObligationPartyRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Gravação de coobrigados comum ao cadastro manual e à carga (auditoria, evento de negócio e
 * métrica), na transação do chamador, que já validou o escopo.
 */
@Component
class ObligationPartyRecorder {

    static final String ENTITY = "obligation_party";

    private final ObligationPartyRepository parties;
    private final AuditTrail auditTrail;
    private final BusinessEventLogger businessEvents;
    private final DebtMetrics metrics;

    ObligationPartyRecorder(ObligationPartyRepository parties, AuditTrail auditTrail, BusinessEventLogger businessEvents,
                            DebtMetrics metrics) {
        this.parties = parties;
        this.auditTrail = auditTrail;
        this.businessEvents = businessEvents;
        this.metrics = metrics;
    }

    ObligationParty register(OriginContract contract, Debtor person, ObligationRole role, LocalDate sinceDate,
                             String source) {
        Guard.requireState(!person.isAnonymized(), "debtor.anonymized", "Pessoa anonimizada não pode ser coobrigada");
        ObligationParty party = parties.save(new ObligationParty(contract.getCreditorId(), contract.getId(),
                contract.getDebtorId(), person.getId(), role, sinceDate));
        auditTrail.record(AuditEntry.of("obligation_party.created", ENTITY, party.getId())
                .creditor(party.getCreditorId())
                .created("originContractId", contract.getId())
                .created("debtorId", person.getId())
                .created("role", role)
                .created("sinceDate", sinceDate)
                .meta("source", source));
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.OBLIGATION_PARTY_REGISTERED)
                .creditor(contract.getCreditorId()).portfolio(contract.getPortfolioId()).debtor(person.getId())
                .reference(ENTITY, party.getId())
                .attr("role", role).attr("originContractId", contract.getId())
                .attr("principalDebtorId", contract.getDebtorId()).attr("personType", person.getPersonType())
                .attr("source", source));
        metrics.obligationPartyRegistered(role, source);
        return party;
    }

    /** @return true se a data de início mudou */
    boolean updateSince(ObligationParty party, LocalDate sinceDate, String source) {
        LocalDate previous = party.getSinceDate();
        if (!party.updateSince(sinceDate)) {
            return false;
        }
        parties.save(party);
        auditTrail.record(AuditEntry.of("obligation_party.updated", ENTITY, party.getId())
                .creditor(party.getCreditorId())
                .change("sinceDate", previous, party.getSinceDate())
                .meta("source", source));
        return true;
    }

    void end(ObligationParty party, LocalDate endedOn, String reason) {
        party.end(endedOn, reason);
        parties.save(party);
        auditTrail.record(AuditEntry.of("obligation_party.ended", ENTITY, party.getId())
                .creditor(party.getCreditorId()).reason(party.getEndReason())
                .change("endedOn", null, party.getEndedOn()));
    }
}
