package br.com.gestorcobranca.debt.application.service;

import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.ContactRecord;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.DebtorRecord;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.Outcome;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.UpsertResult;
import br.com.gestorcobranca.debt.domain.model.ContactLabel;
import br.com.gestorcobranca.debt.domain.model.ContactSource;
import br.com.gestorcobranca.debt.domain.model.ContactType;
import br.com.gestorcobranca.debt.domain.model.ContactValue;
import br.com.gestorcobranca.debt.domain.model.Debtor;
import br.com.gestorcobranca.debt.domain.repository.DebtorRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Document;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Cadastro de pessoas (devedores principais e coobrigados) consolidado pelo documento (RB01):
 * o mesmo fluxo atende o cadastro manual, a carga de devedores e a inclusão de coobrigados.
 * Toda criação e alteração é auditada com nome mascarado.
 */
@Component
class DebtorRegistry {

    private final DebtorRepository debtors;
    private final ContactRegistry contactRegistry;
    private final PersonalDataIndex index;
    private final AuditTrail auditTrail;

    DebtorRegistry(DebtorRepository debtors, ContactRegistry contactRegistry, PersonalDataIndex index,
                   AuditTrail auditTrail) {
        this.debtors = debtors;
        this.contactRegistry = contactRegistry;
        this.index = index;
        this.auditTrail = auditTrail;
    }

    Optional<Debtor> findByDocument(Document document) {
        return debtors.findByDocumentHash(index.document(document));
    }

    /** Cria o devedor; o chamador garante que o documento ainda não está cadastrado. */
    Debtor create(Document document, String name, LocalDate birthDate, String notes, ContactSource source) {
        Debtor debtor = debtors.save(new Debtor(document, index.document(document), name, birthDate, notes));
        auditTrail.record(AuditEntry.of("debtor.created", "debtor", debtor.getId())
                .created("personType", debtor.getPersonType())
                .created("document", document.masked())
                .created("name", DebtViews.maskName(debtor.getName()))
                .created("birthDate", DebtViews.maskDate(debtor.getBirthDate()))
                .meta("source", source));
        return debtor;
    }

    /** Pessoa informada manualmente (ex.: coobrigado): usa o cadastro existente sem alterá-lo, ou cria. */
    Debtor findOrCreate(Document document, String name, LocalDate birthDate) {
        return findByDocument(document).orElseGet(() -> create(document, name, birthDate, null, ContactSource.MANUAL));
    }

    /**
     * Pessoa vinda de carga: casa pelo documento, atualiza nome/nascimento informados e acrescenta
     * contatos novos. Contato inválido não impede a gravação: volta como aviso.
     */
    UpsertResult upsertFromImport(DebtorRecord record) {
        Document document = Document.parse(record.document());
        Optional<Debtor> existing = findByDocument(document);
        Outcome outcome;
        Debtor debtor;
        if (existing.isEmpty()) {
            debtor = create(document, record.name(), record.birthDate(), null, ContactSource.IMPORT);
            outcome = Outcome.CREATED;
        } else {
            debtor = existing.get();
            String previousName = debtor.getName();
            if (debtor.updateFromImport(record.name(), record.birthDate())) {
                debtors.save(debtor);
                AuditEntry audit = AuditEntry.of("debtor.updated", "debtor", debtor.getId()).meta("source", ContactSource.IMPORT);
                if (!previousName.equals(debtor.getName())) {
                    audit.change("name", DebtViews.maskName(previousName), DebtViews.maskName(debtor.getName()));
                } else {
                    audit.meta("birthDateChanged", true);
                }
                auditTrail.record(audit);
                outcome = Outcome.UPDATED;
            } else {
                outcome = Outcome.UNCHANGED;
            }
        }

        List<String> warnings = new ArrayList<>();
        boolean contactsAdded = false;
        for (ContactRecord contact : record.contacts()) {
            try {
                ContactType type = DebtViews.parseEnum(ContactType.class, contact.type(), "contact.type_invalid", "Tipo de contato");
                ContactValue value = ContactValue.parse(type, contact.value());
                // sem classificação na carga, celular é identificado pelo formato do número
                ContactLabel label = contact.label() == null || contact.label().isBlank()
                        ? (value.isMobilePhone() ? ContactLabel.MOBILE : ContactLabel.OTHER)
                        : DebtViews.parseEnum(ContactLabel.class, contact.label(), "contact.label_invalid", "Classificação do contato");
                boolean whatsapp = contact.whatsapp() && type == ContactType.PHONE;
                contactsAdded |= contactRegistry.addIfAbsent(debtor, value, label, whatsapp, ContactSource.IMPORT, null)
                        .isPresent();
            } catch (BusinessRuleException e) {
                // contato inválido não impede a carga do devedor: volta como aviso para o relatório de erros
                warnings.add(e.code());
            }
        }
        if (outcome == Outcome.UNCHANGED && contactsAdded) {
            outcome = Outcome.UPDATED;
        }
        return new UpsertResult(debtor.getId(), outcome, warnings);
    }
}
