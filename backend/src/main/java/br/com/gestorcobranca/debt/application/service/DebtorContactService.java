package br.com.gestorcobranca.debt.application.service;

import br.com.gestorcobranca.debt.application.port.in.DebtorContactUseCases;
import br.com.gestorcobranca.debt.domain.model.ContactLabel;
import br.com.gestorcobranca.debt.domain.model.ContactSource;
import br.com.gestorcobranca.debt.domain.model.ContactStatus;
import br.com.gestorcobranca.debt.domain.model.ContactType;
import br.com.gestorcobranca.debt.domain.model.ContactValue;
import br.com.gestorcobranca.debt.domain.model.Debtor;
import br.com.gestorcobranca.debt.domain.model.DebtorContact;
import br.com.gestorcobranca.debt.domain.repository.DebtorContactRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.security.Permission;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional
class DebtorContactService implements DebtorContactUseCases {

    private final DebtorContactRepository contacts;
    private final ContactRegistry registry;
    private final PersonalDataIndex index;
    private final DebtorVisibility visibility;
    private final AccessScope accessScope;
    private final CurrentActor currentActor;
    private final AuditTrail auditTrail;
    private final AccessLogger accessLogger;
    private final Clock clock;

    DebtorContactService(DebtorContactRepository contacts, ContactRegistry registry, PersonalDataIndex index,
                         DebtorVisibility visibility, AccessScope accessScope, CurrentActor currentActor,
                         AuditTrail auditTrail, AccessLogger accessLogger, Clock clock) {
        this.contacts = contacts;
        this.registry = registry;
        this.index = index;
        this.visibility = visibility;
        this.accessScope = accessScope;
        this.currentActor = currentActor;
        this.auditTrail = auditTrail;
        this.accessLogger = accessLogger;
        this.clock = clock;
    }

    @Override
    public ContactView add(UUID debtorId, AddContactCommand command) {
        Debtor debtor = visibility.requireVisible(debtorId);
        ContactType type = DebtViews.parseEnum(ContactType.class, command.type(), "contact.type_invalid", "Tipo de contato");
        ContactLabel label = command.label() == null ? ContactLabel.OTHER
                : DebtViews.parseEnum(ContactLabel.class, command.label(), "contact.label_invalid", "Classificação do contato");
        ContactSource source = command.source() == null ? ContactSource.MANUAL
                : DebtViews.parseEnum(ContactSource.class, command.source(), "contact.source_invalid", "Origem do contato");
        if (source != ContactSource.MANUAL && source != ContactSource.DEBTOR) {
            throw new BusinessRuleException("contact.source_invalid", "Origem permitida: MANUAL ou DEBTOR");
        }
        ContactValue value = ContactValue.parse(type, command.value());
        DebtorContact contact = registry.addIfAbsent(debtor, value, label, command.whatsapp(), source, command.priority())
                .orElseThrow(() -> new ConflictException("contact.duplicate", "Contato já cadastrado para o devedor"));
        return DebtViews.contact(contact);
    }

    @Override
    public ContactView update(UUID debtorId, UUID contactId, UpdateContactCommand command) {
        DebtorContact contact = loadVisible(debtorId, contactId);
        ContactLabel label = DebtViews.parseEnum(ContactLabel.class, command.label(), "contact.label_invalid",
                "Classificação do contato");
        AuditEntry audit = AuditEntry.of("debtor_contact.updated", "debtor_contact", contactId)
                .meta("debtorId", debtorId)
                .change("label", contact.getLabel(), label)
                .change("whatsapp", contact.isWhatsapp(), command.whatsapp())
                .change("priority", contact.getPriority(), command.priority());
        contact.classify(label, command.whatsapp(), command.priority());
        contacts.save(contact);
        auditTrail.record(audit);
        return DebtViews.contact(contact);
    }

    @Override
    public ContactView markInvalid(UUID debtorId, UUID contactId, String reason) {
        DebtorContact contact = loadVisible(debtorId, contactId);
        ContactStatus previous = contact.getStatus();
        contact.markInvalid(reason, Instant.now(clock));
        contacts.save(contact);
        auditTrail.record(AuditEntry.of("debtor_contact.invalidated", "debtor_contact", contactId)
                .meta("debtorId", debtorId).reason(contact.getInvalidReason())
                .change("status", previous, contact.getStatus()));
        return DebtViews.contact(contact);
    }

    @Override
    public ContactView markValid(UUID debtorId, UUID contactId) {
        DebtorContact contact = loadVisible(debtorId, contactId);
        ContactStatus previous = contact.getStatus();
        contact.markValid(Instant.now(clock));
        contacts.save(contact);
        auditTrail.record(AuditEntry.of("debtor_contact.validated", "debtor_contact", contactId)
                .meta("debtorId", debtorId)
                .change("status", previous, contact.getStatus()));
        return DebtViews.contact(contact);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ContactView> list(UUID debtorId) {
        visibility.requireVisible(debtorId);
        return contacts.findByDebtorIdOrderByTypeAscPriorityAscCreatedAtAsc(debtorId).stream()
                .map(DebtViews::contact).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public RevealedContact revealContact(UUID debtorId, UUID contactId) {
        accessScope.requirePermission(Permission.DEBTOR_SENSITIVE_VIEW);
        DebtorContact contact = loadVisible(debtorId, contactId);
        accessLogger.log(AccessEvent.of(AccessEventType.SENSITIVE_DATA_VIEWED, currentActor.get(),
                Map.of("resource", "debtor_contact", "resourceId", contactId.toString(), "debtorId", debtorId.toString(),
                        "field", contact.getType().name())));
        return new RevealedContact(contactId, debtorId, contact.getType().name(), contact.getValue());
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChannelContact> contactsForChannel(UUID debtorId, String type) {
        visibility.requireVisible(debtorId);
        ContactType contactType = DebtViews.parseEnum(ContactType.class, type, "contact.type_invalid", "Tipo de contato");
        return contacts.findByDebtorIdAndTypeOrderByPriorityAscCreatedAtAsc(debtorId, contactType).stream()
                .filter(c -> c.getStatus().isUsable())
                .map(c -> new ChannelContact(c.getId(), c.getType().name(), c.getValue(), c.getLabel().name(),
                        c.isWhatsapp(), c.getPriority(), c.getStatus().name()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ContactFingerprint fingerprint(String type, String value) {
        ContactType contactType = DebtViews.parseEnum(ContactType.class, type, "contact.type_invalid", "Tipo de contato");
        if (contactType == ContactType.ADDRESS) {
            throw new BusinessRuleException("contact.type_invalid", "Somente telefone ou e-mail");
        }
        ContactValue contactValue = ContactValue.parse(contactType, value);
        return new ContactFingerprint(contactType.name(), index.contact(contactValue), contactValue.masked());
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Optional<ContactFingerprint> tryFingerprint(String type, String value) {
        ContactType contactType;
        try {
            contactType = ContactType.valueOf(type);
        } catch (IllegalArgumentException | NullPointerException e) {
            return java.util.Optional.empty();
        }
        if (contactType == ContactType.ADDRESS || value == null || value.isBlank()) {
            return java.util.Optional.empty();
        }
        try {
            ContactValue contactValue = ContactValue.parse(contactType, value);
            return java.util.Optional.of(new ContactFingerprint(contactType.name(), index.contact(contactValue),
                    contactValue.masked()));
        } catch (BusinessRuleException e) {
            return java.util.Optional.empty();
        }
    }

    private DebtorContact loadVisible(UUID debtorId, UUID contactId) {
        visibility.requireVisible(debtorId);
        return contacts.findById(contactId)
                .filter(c -> c.getDebtorId().equals(debtorId))
                .orElseThrow(() -> ResourceNotFoundException.of("debtor_contact", contactId));
    }
}
