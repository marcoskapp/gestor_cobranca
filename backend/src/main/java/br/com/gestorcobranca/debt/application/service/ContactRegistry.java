package br.com.gestorcobranca.debt.application.service;

import br.com.gestorcobranca.debt.domain.model.ContactLabel;
import br.com.gestorcobranca.debt.domain.model.ContactSource;
import br.com.gestorcobranca.debt.domain.model.ContactValue;
import br.com.gestorcobranca.debt.domain.model.Debtor;
import br.com.gestorcobranca.debt.domain.model.DebtorContact;
import br.com.gestorcobranca.debt.domain.repository.DebtorContactRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.Guard;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Inclusão de contatos com deduplicação por blind index, comum ao cadastro manual e à carga.
 * Toda inclusão é auditada com o valor mascarado.
 */
@Component
class ContactRegistry {

    private final DebtorContactRepository contacts;
    private final PersonalDataIndex index;
    private final AuditTrail auditTrail;

    ContactRegistry(DebtorContactRepository contacts, PersonalDataIndex index, AuditTrail auditTrail) {
        this.contacts = contacts;
        this.index = index;
        this.auditTrail = auditTrail;
    }

    /**
     * Inclui o contato se o devedor ainda não o tiver.
     *
     * @param priority ordem de uso; nulo = após os contatos existentes do mesmo tipo
     * @return contato criado; vazio se já existia
     */
    Optional<DebtorContact> addIfAbsent(Debtor debtor, ContactValue value, ContactLabel label, boolean whatsapp,
                                        ContactSource source, Integer priority) {
        Guard.requireState(!debtor.isAnonymized(), "debtor.anonymized", "Devedor anonimizado não recebe contatos");
        String hash = index.contact(value);
        if (contacts.existsByDebtorIdAndTypeAndValueHash(debtor.getId(), value.type(), hash)) {
            return Optional.empty();
        }
        int effectivePriority = priority != null ? priority
                : contacts.findByDebtorIdAndTypeOrderByPriorityAscCreatedAtAsc(debtor.getId(), value.type()).stream()
                        .mapToInt(DebtorContact::getPriority).max().orElse(-1) + 1;
        DebtorContact contact = contacts.save(new DebtorContact(debtor.getId(), value, hash, label, whatsapp, source,
                effectivePriority));
        auditTrail.record(AuditEntry.of("debtor_contact.created", "debtor_contact", contact.getId())
                .created("debtorId", debtor.getId())
                .created("type", contact.getType())
                .created("value", contact.maskedValue())
                .created("label", contact.getLabel())
                .created("whatsapp", contact.isWhatsapp())
                .created("source", contact.getSource())
                .created("priority", contact.getPriority()));
        return Optional.of(contact);
    }
}
