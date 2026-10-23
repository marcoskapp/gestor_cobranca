package br.com.gestorcobranca.debt.application.service;

import br.com.gestorcobranca.debt.application.port.in.ObligationPartyUseCases;
import br.com.gestorcobranca.debt.domain.model.Debtor;
import br.com.gestorcobranca.debt.domain.model.ObligationParty;
import br.com.gestorcobranca.debt.domain.model.ObligationRole;
import br.com.gestorcobranca.debt.domain.model.OriginContract;
import br.com.gestorcobranca.debt.domain.repository.DebtorRepository;
import br.com.gestorcobranca.debt.domain.repository.ObligationPartyRepository;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Document;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
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

    private final ObligationPartyRepository parties;
    private final DebtorRepository debtors;
    private final DebtorRegistry debtorRegistry;
    private final OriginContractAccess contractAccess;
    private final ObligationPartyRecorder recorder;

    ObligationPartyService(ObligationPartyRepository parties, DebtorRepository debtors, DebtorRegistry debtorRegistry,
                           OriginContractAccess contractAccess, ObligationPartyRecorder recorder) {
        this.parties = parties;
        this.debtors = debtors;
        this.debtorRegistry = debtorRegistry;
        this.contractAccess = contractAccess;
        this.recorder = recorder;
    }

    @Override
    @Transactional(readOnly = true)
    public List<PartyView> list(UUID contractId) {
        OriginContract contract = contractAccess.requireVisible(contractId);
        List<ObligationParty> coObligors = parties.findByOriginContractIdOrderByCreatedAtAsc(contractId);
        List<UUID> personIds = new ArrayList<>();
        personIds.add(contract.getDebtorId());
        coObligors.forEach(p -> personIds.add(p.getDebtorId()));
        Map<UUID, Debtor> people = debtors.findAllByIdIn(personIds.stream().distinct().toList()).stream()
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
        if (parties.findByOriginContractIdAndDebtorIdAndRole(contractId, person.getId(), role).isPresent()) {
            throw new ConflictException("obligation_party.duplicate", "Pessoa já cadastrada neste papel no contrato");
        }
        return view(recorder.register(contract, person, role, command.sinceDate(), ChangeSource.MANUAL), person);
    }

    @Override
    public PartyView end(UUID contractId, UUID partyId, LocalDate endedOn, String reason) {
        contractAccess.requireForCommand(contractId);
        ObligationParty party = parties.findById(partyId).filter(p -> p.getOriginContractId().equals(contractId))
                .orElseThrow(() -> ResourceNotFoundException.of(ObligationPartyRecorder.ENTITY, partyId));
        recorder.end(party, endedOn, reason);
        return view(party, debtors.findById(party.getDebtorId()).orElseThrow());
    }

    private static PartyView view(ObligationParty p, Debtor person) {
        return new PartyView(p.getId(), p.getOriginContractId(), person.getId(), person.getName(), person.maskedDocument(),
                p.getRole().name(), p.getSinceDate(), p.getEndedOn(), p.getEndReason(), p.isActive());
    }
}
