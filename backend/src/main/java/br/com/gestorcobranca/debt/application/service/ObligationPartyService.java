package br.com.gestorcobranca.debt.application.service;

import br.com.gestorcobranca.debt.application.port.in.ObligationPartyUseCases;
import br.com.gestorcobranca.debt.domain.model.Debtor;
import br.com.gestorcobranca.debt.domain.model.ObligationParty;
import br.com.gestorcobranca.debt.domain.model.ObligationRole;
import br.com.gestorcobranca.debt.domain.model.OriginContract;
import br.com.gestorcobranca.debt.domain.repository.DebtorRepository;
import br.com.gestorcobranca.debt.domain.repository.ObligationPartyRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Document;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Transactional
class ObligationPartyService implements ObligationPartyUseCases {

    static final String ENTITY = "obligation_party";

    private final ObligationPartyRepository parties;
    private final DebtorRepository debtors;
    private final DebtorRegistry debtorRegistry;
    private final OriginContractAccess contractAccess;
    private final AuditTrail auditTrail;
    private final BusinessEventLogger businessEvents;

    ObligationPartyService(ObligationPartyRepository parties, DebtorRepository debtors, DebtorRegistry debtorRegistry,
                           OriginContractAccess contractAccess, AuditTrail auditTrail, BusinessEventLogger businessEvents) {
        this.parties = parties;
        this.debtors = debtors;
        this.debtorRegistry = debtorRegistry;
        this.contractAccess = contractAccess;
        this.auditTrail = auditTrail;
        this.businessEvents = businessEvents;
    }

    @Override
    @Transactional(readOnly = true)
    public List<PartyView> list(UUID contractId) {
        OriginContract contract = contractAccess.requireVisible(contractId);
        List<ObligationParty> coObligors = parties.findByOriginContractIdOrderByCreatedAtAsc(contractId);
        List<UUID> personIds = new ArrayList<>();
        personIds.add(contract.getDebtorId());
        coObligors.forEach(p -> personIds.add(p.getDebtorId()));
        Map<UUID, Debtor> people = personIds.stream().distinct().map(id -> debtors.findById(id).orElseThrow())
                .collect(Collectors.toMap(Debtor::getId, Function.identity()));
        List<PartyView> views = new ArrayList<>();
        Debtor principal = people.get(contract.getDebtorId());
        views.add(new PartyView(null, contractId, principal.getId(), principal.getName(), principal.maskedDocument(),
                ObligationRole.PRINCIPAL.name(), contract.getSignedAt(), null, null, true));
        coObligors.forEach(p -> views.add(view(p, people.get(p.getDebtorId()))));
        return views;
    }

    @Override
    public PartyView add(UUID contractId, AddPartyCommand command) {
        OriginContract contract = contractAccess.requireForCommand(contractId);
        ObligationRole role = DebtViews.parseEnum(ObligationRole.class, command.role(), "obligation_party.role_invalid",
                "Papel na obrigação");
        Debtor person = debtorRegistry.findOrCreate(Document.parse(command.document()), command.name(), command.birthDate());
        Guard.requireState(!person.isAnonymized(), "debtor.anonymized", "Pessoa anonimizada não pode ser coobrigada");
        if (parties.findByOriginContractIdAndDebtorIdAndRole(contractId, person.getId(), role).isPresent()) {
            throw new ConflictException("obligation_party.duplicate", "Pessoa já cadastrada neste papel no contrato");
        }
        return view(register(contract, person, role, command.sinceDate(), "MANUAL"), person);
    }

    @Override
    public PartyView end(UUID contractId, UUID partyId, LocalDate endedOn, String reason) {
        contractAccess.requireForCommand(contractId);
        ObligationParty party = parties.findById(partyId).filter(p -> p.getOriginContractId().equals(contractId))
                .orElseThrow(() -> ResourceNotFoundException.of(ENTITY, partyId));
        party.end(endedOn, reason);
        parties.save(party);
        auditTrail.record(AuditEntry.of("obligation_party.ended", ENTITY, partyId)
                .creditor(party.getCreditorId()).reason(party.getEndReason())
                .change("endedOn", null, party.getEndedOn()));
        return view(party, debtors.findById(party.getDebtorId()).orElseThrow());
    }

    /** Inclui o coobrigado com auditoria e evento de negócio (também usado pela carga). */
    ObligationParty register(OriginContract contract, Debtor person, ObligationRole role, LocalDate sinceDate,
                             String source) {
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
                .attr("principalDebtorId", contract.getDebtorId()).attr("source", source));
        return party;
    }

    private static PartyView view(ObligationParty p, Debtor person) {
        return new PartyView(p.getId(), p.getOriginContractId(), person.getId(), person.getName(), person.maskedDocument(),
                p.getRole().name(), p.getSinceDate(), p.getEndedOn(), p.getEndReason(), p.isActive());
    }
}
