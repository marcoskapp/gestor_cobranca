package br.com.gestorcobranca.collection.application.service;

import br.com.gestorcobranca.collection.application.port.in.ContactAvailabilityQueries;
import br.com.gestorcobranca.collection.domain.model.CollectionCase;
import br.com.gestorcobranca.collection.domain.model.EligibilityReason;
import br.com.gestorcobranca.collection.domain.model.InteractionChannel;
import br.com.gestorcobranca.compliance.application.port.in.ContactGate;
import br.com.gestorcobranca.compliance.application.port.in.ContactGate.ContactDecision;
import br.com.gestorcobranca.compliance.application.port.in.ContactGate.ContactRequest;
import br.com.gestorcobranca.debt.application.port.in.DebtorContactUseCases;
import br.com.gestorcobranca.debt.application.port.in.DebtorContactUseCases.ChannelContact;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.Permission;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Consulta do porteiro a partir do caso. O valor do contato escolhido é descriptografado só para
 * o porteiro calcular o blind index e não sai deste método (nem em resposta, nem em log).
 */
@Service
@Transactional(readOnly = true)
class ContactAvailabilityService implements ContactAvailabilityQueries {

    /** Ordem de exibição dos canais. */
    private static final List<InteractionChannel> CHANNELS = List.of(InteractionChannel.PHONE,
            InteractionChannel.WHATSAPP, InteractionChannel.SMS, InteractionChannel.EMAIL);

    private final CaseAccess access;
    private final ContactGate gate;
    private final DebtorContactUseCases debtorContacts;
    private final AccessScope accessScope;
    private final Clock clock;

    ContactAvailabilityService(CaseAccess access, ContactGate gate, DebtorContactUseCases debtorContacts,
                               AccessScope accessScope, Clock clock) {
        this.access = access;
        this.gate = gate;
        this.debtorContacts = debtorContacts;
        this.accessScope = accessScope;
        this.clock = clock;
    }

    @Override
    public ContactAvailability check(UUID caseId, String channel, UUID contactId) {
        accessScope.requirePermission(Permission.CASE_WORK);
        CollectionCase c = access.visible(caseId);
        List<InteractionChannel> channels = channel == null || channel.isBlank() ? CHANNELS : List.of(parse(channel));
        Map<String, Optional<ChannelContact>> contactsByType = new HashMap<>();
        if (contactId != null) {
            boolean found = false;
            for (String type : List.of("PHONE", "EMAIL")) {
                Optional<ChannelContact> contact = debtorContacts.contactsForChannel(c.getDebtorId(), type).stream()
                        .filter(k -> k.contactId().equals(contactId)).findFirst();
                contactsByType.put(type, contact);
                found |= contact.isPresent();
            }
            Guard.require(found, "case.contact_invalid", "O contato informado não pertence ao devedor do caso ou está inválido");
        }

        List<String> caseReasons = c.eligibility().reasons().stream()
                .filter(r -> r != EligibilityReason.CONTACT_RESTRICTED)
                .map(Enum::name).sorted().toList();
        boolean caseEligible = c.getStatus().isActive() && caseReasons.isEmpty();
        Instant now = Instant.now(clock);
        List<ChannelAvailability> result = new ArrayList<>(channels.size());
        for (InteractionChannel ch : channels) {
            Optional<ChannelContact> contact = ch.contactType().flatMap(t -> contactsByType.getOrDefault(t, Optional.empty()));
            ContactDecision decision = gate.evaluate(ContactRequest.of(c.getDebtorId(), c.getCreditorId(), ch.name(),
                    contact.map(ChannelContact::value).orElse(null), now, "OPERATOR"));
            result.add(new ChannelAvailability(ch.name(), caseEligible && decision.allowed(),
                    decision.reasons().stream().map(Enum::name).toList(), decision.nextAllowedAt(),
                    contact.map(ChannelContact::contactId).orElse(null)));
        }
        return new ContactAvailability(c.getId(), now, caseEligible, caseReasons, result);
    }

    private static InteractionChannel parse(String channel) {
        InteractionChannel parsed = CollectionEnums.parse(InteractionChannel.class, channel, "case.contact_channel_invalid",
                "Canal");
        if (!CHANNELS.contains(parsed)) {
            throw new BusinessRuleException("case.contact_channel_invalid",
                    "Canal sem consulta ao porteiro: use PHONE, WHATSAPP, SMS ou EMAIL");
        }
        return parsed;
    }
}
