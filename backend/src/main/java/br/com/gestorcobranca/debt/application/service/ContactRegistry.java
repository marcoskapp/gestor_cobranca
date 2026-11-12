package br.com.gestorcobranca.debt.application.service;

import br.com.gestorcobranca.debt.domain.model.ContactLabel;
import br.com.gestorcobranca.debt.domain.model.ContactPersonRole;
import br.com.gestorcobranca.debt.domain.model.ContactSource;
import br.com.gestorcobranca.debt.domain.model.ContactValue;
import br.com.gestorcobranca.debt.domain.model.Debtor;
import br.com.gestorcobranca.debt.domain.model.DebtorContact;
import br.com.gestorcobranca.debt.domain.repository.DebtorContactRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.Guard;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

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
        return addIfAbsent(debtor, value, label, whatsapp, source, priority, null);
    }

    /** Pessoa de contato (B2) informada com o contato. */
    record Person(String name, ContactPersonRole role, boolean decisionMaker) {
    }

    /**
     * Como {@link #addIfAbsent(Debtor, ContactValue, ContactLabel, boolean, ContactSource, Integer)}, já com a pessoa de
     * contato (nula = sem pessoa).
     */
    Optional<DebtorContact> addIfAbsent(Debtor debtor, ContactValue value, ContactLabel label, boolean whatsapp,
                                        ContactSource source, Integer priority, Person person) {
        Guard.requireState(!debtor.isAnonymized(), "debtor.anonymized", "Devedor anonimizado não recebe contatos");
        String hash = index.contact(value);
        if (contacts.existsByDebtorIdAndTypeAndValueHash(debtor.getId(), value.type(), hash)) {
            return Optional.empty();
        }
        return Optional.of(create(debtor, value, hash, label, whatsapp, source, priority, withPerson(person)));
    }

    /**
     * Contato vindo de carga (docs/tecnico/desenho/contatos-recebidos.md §3): cria com a carga de origem — e, se o
     * devedor já existia antes da carga, com a janela de novidade — ou registra o reenvio do contato que o devedor já
     * tem. Reprocessar a mesma carga não muda nada.
     *
     * @param afterRegistration o devedor existia antes desta carga
     * @param person            pessoa de contato da carga (nula = sem pessoa); no contato existente, quem chama usa
     *                          {@link #completePerson} (a pessoa registrada manualmente nunca é substituída)
     */
    ImportReceipt receiveFromImport(Debtor debtor, ContactValue value, ContactLabel label, boolean whatsapp,
                                    UUID importBatchId, boolean afterRegistration, Duration window, Instant now,
                                    Person person) {
        Guard.requireState(!debtor.isAnonymized(), "debtor.anonymized", "Devedor anonimizado não recebe contatos");
        String hash = index.contact(value);
        Optional<DebtorContact> existing = contacts.findByDebtorIdAndTypeAndValueHash(debtor.getId(), value.type(), hash);
        if (existing.isPresent()) {
            DebtorContact contact = existing.get();
            boolean resent = contact.resentByImport(importBatchId, now);
            if (resent) {
                contacts.save(contact);
            }
            return new ImportReceipt(contact, false, resent);
        }
        DebtorContact contact = create(debtor, value, hash, label, whatsapp, ContactSource.IMPORT, null,
                c -> {
                    c.receivedByImport(importBatchId, afterRegistration, window, now);
                    if (person != null) {
                        c.assignPerson(person.name(), person.role(), person.decisionMaker());
                    }
                });
        return new ImportReceipt(contact, true, false);
    }

    private static Consumer<DebtorContact> withPerson(Person person) {
        return person == null ? null : c -> c.assignPerson(person.name(), person.role(), person.decisionMaker());
    }

    private DebtorContact create(Debtor debtor, ContactValue value, String hash, ContactLabel label, boolean whatsapp,
                                 ContactSource source, Integer priority, Consumer<DebtorContact> beforeSave) {
        int effectivePriority = priority != null ? priority
                : contacts.findByDebtorIdAndTypeOrderByPriorityAscCreatedAtAsc(debtor.getId(), value.type()).stream()
                        .mapToInt(DebtorContact::getPriority).max().orElse(-1) + 1;
        DebtorContact created = new DebtorContact(debtor.getId(), value, hash, label, whatsapp, source, effectivePriority);
        if (beforeSave != null) {
            beforeSave.accept(created);
        }
        DebtorContact contact = contacts.save(created);
        auditTrail.record(AuditEntry.of("debtor_contact.created", "debtor_contact", contact.getId())
                .created("debtorId", debtor.getId())
                .created("type", contact.getType())
                .created("value", contact.maskedValue())
                .created("label", contact.getLabel())
                .created("whatsapp", contact.isWhatsapp())
                .created("source", contact.getSource())
                .created("priority", contact.getPriority())
                .created("personName", DebtViews.maskName(contact.getPersonName()))
                .created("personRole", contact.getPersonRole())
                .created("decisionMaker", contact.isDecisionMaker())
                .created("receivedAfterRegistration", contact.isReceivedAfterRegistration()));
        return contact;
    }

    /**
     * @param created contato criado agora (senão, já existia)
     * @param resent  reenvio registrado (contato existente enviado por outra carga)
     */
    record ImportReceipt(DebtorContact contact, boolean created, boolean resent) {

        /** Contato novo para devedor já existente (abre a janela de novidade). */
        boolean receivedNew() {
            return created && contact.isReceivedAfterRegistration();
        }
    }

    /**
     * Carga: contato já cadastrado sem pessoa de contato recebe a pessoa informada (a pessoa registrada manualmente
     * nunca é substituída pela carga).
     *
     * @return verdadeiro se a pessoa foi registrada agora
     */
    boolean completePerson(Debtor debtor, ContactValue value, Person person) {
        if (person == null || (person.name() == null && person.role() == null)) {
            return false;
        }
        Optional<DebtorContact> existing = contacts.findByDebtorIdAndTypeAndValueHash(debtor.getId(), value.type(),
                index.contact(value));
        if (existing.isEmpty() || existing.get().hasPerson()) {
            return false;
        }
        DebtorContact contact = existing.get();
        contact.assignPerson(person.name(), person.role(), person.decisionMaker());
        contacts.save(contact);
        auditTrail.record(AuditEntry.of("debtor_contact.updated", "debtor_contact", contact.getId())
                .meta("debtorId", debtor.getId()).meta("source", ContactSource.IMPORT)
                .change("personName", null, DebtViews.maskName(contact.getPersonName()))
                .change("personRole", null, contact.getPersonRole()));
        return true;
    }
}
