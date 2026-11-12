package br.com.gestorcobranca.debt.application.service;

import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.ContactRecord;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.DebtorRecord;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.Outcome;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.RepresentativeRecord;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.UpsertResult;
import br.com.gestorcobranca.debt.domain.model.ContactLabel;
import br.com.gestorcobranca.debt.domain.model.ContactPersonRole;
import br.com.gestorcobranca.debt.domain.model.RepresentativeRole;
import br.com.gestorcobranca.debt.domain.model.ContactSource;
import br.com.gestorcobranca.debt.domain.model.ContactType;
import br.com.gestorcobranca.debt.domain.model.ContactValue;
import br.com.gestorcobranca.debt.domain.model.Debtor;
import br.com.gestorcobranca.debt.domain.repository.DebtorRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Document;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Cadastro de pessoas (devedores principais e coobrigados) consolidado pelo documento (RB01):
 * o mesmo fluxo atende o cadastro manual, a carga de devedores e a inclusão de coobrigados.
 * Toda criação e alteração é auditada com nome mascarado.
 */
@Component
class DebtorRegistry {

    private final DebtorRepository debtors;
    private final ContactRegistry contactRegistry;
    private final RepresentativeRegistry representativeRegistry;
    private final PersonalDataIndex index;
    private final AuditTrail auditTrail;

    private final ContactNoveltyTracker novelty;
    private final BusinessEventLogger businessEvents;
    private final DebtMetrics metrics;
    private final Clock clock;

    DebtorRegistry(DebtorRepository debtors, ContactRegistry contactRegistry,
                   RepresentativeRegistry representativeRegistry, PersonalDataIndex index, AuditTrail auditTrail,
                   ContactNoveltyTracker novelty, BusinessEventLogger businessEvents, DebtMetrics metrics, Clock clock) {
        this.debtors = debtors;
        this.contactRegistry = contactRegistry;
        this.representativeRegistry = representativeRegistry;
        this.index = index;
        this.auditTrail = auditTrail;
        this.novelty = novelty;
        this.businessEvents = businessEvents;
        this.metrics = metrics;
        this.clock = clock;
    }

    Optional<Debtor> findByDocument(Document document) {
        return debtors.findByDocumentHash(index.document(document));
    }

    /** Cria o devedor; o chamador garante que o documento ainda não está cadastrado. */
    Debtor create(Document document, String name, LocalDate birthDate, String notes, ContactSource source) {
        return create(document, name, null, birthDate, notes, source, null);
    }

    /** @param tradeName nome fantasia (só CNPJ) */
    Debtor create(Document document, String name, String tradeName, LocalDate birthDate, String notes,
                  ContactSource source) {
        return create(document, name, tradeName, birthDate, notes, source, null);
    }

    /**
     * @param tradeName     nome fantasia (só CNPJ)
     * @param importBatchId carga que cria o devedor (nulo fora de carga)
     */
    private Debtor create(Document document, String name, String tradeName, LocalDate birthDate, String notes,
                          ContactSource source, UUID importBatchId) {
        Debtor created = new Debtor(document, index.document(document), index.documentRoot(document), name, tradeName,
                birthDate, notes);
        if (importBatchId != null) {
            created.createdByImport(importBatchId);
        }
        Debtor debtor = debtors.save(created);
        AuditEntry audit = AuditEntry.of("debtor.created", "debtor", debtor.getId())
                .created("personType", debtor.getPersonType())
                .created("document", document.masked())
                .created("name", DebtViews.maskName(debtor.getName()))
                .created("birthDate", DebtViews.maskDate(debtor.getBirthDate()))
                .meta("source", source);
        if (debtor.getTradeName() != null) {
            audit.created("tradeName", DebtViews.maskName(debtor.getTradeName()));
        }
        auditTrail.record(audit);
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
        boolean company = document.type() == Document.DocumentType.CNPJ;
        // empresa não tem data de nascimento; nome fantasia só existe para empresa (a carga já avisou a linha)
        LocalDate birthDate = company ? null : record.birthDate();
        String tradeName = company ? record.tradeName() : null;
        Optional<Debtor> existing = findByDocument(document);
        Outcome outcome;
        Debtor debtor;
        if (existing.isEmpty()) {
            debtor = create(document, record.name(), tradeName, birthDate, null, ContactSource.IMPORT,
                    record.importBatchId());
            outcome = Outcome.CREATED;
        } else {
            debtor = existing.get();
            String previousName = debtor.getName();
            LocalDate previousBirthDate = debtor.getBirthDate();
            String previousTradeName = debtor.getTradeName();
            boolean rootAssigned = debtor.assignDocumentRoot(index.documentRoot(document));
            if (debtor.updateFromImport(record.name(), tradeName, birthDate) || rootAssigned) {
                debtors.save(debtor);
                AuditEntry audit = AuditEntry.of("debtor.updated", "debtor", debtor.getId()).meta("source", ContactSource.IMPORT);
                if (!previousName.equals(debtor.getName())) {
                    audit.change("name", DebtViews.maskName(previousName), DebtViews.maskName(debtor.getName()));
                }
                if (!Objects.equals(previousBirthDate, debtor.getBirthDate())) {
                    audit.meta("birthDateChanged", true);
                }
                if (!Objects.equals(previousTradeName, debtor.getTradeName())) {
                    audit.change("tradeName", DebtViews.maskName(previousTradeName), DebtViews.maskName(debtor.getTradeName()));
                }
                if (rootAssigned) {
                    audit.meta("documentRootAssigned", true);
                }
                auditTrail.record(audit);
                outcome = Outcome.UPDATED;
            } else {
                outcome = Outcome.UNCHANGED;
            }
        }

        UUID batchId = record.importBatchId();
        // contato novo só para devedor que já existia antes desta carga (criado à mão ou por outra carga)
        boolean afterRegistration = batchId != null && outcome != Outcome.CREATED && debtor.existedBefore(batchId);
        Duration window = afterRegistration && !record.contacts().isEmpty() ? novelty.window() : null;
        if (window != null) {
            // mesmo bloqueio do uso, da invalidação e da varredura: o "antes" e o instante vêm depois dele
            novelty.lockDebtor(debtor.getId());
        }
        Instant now = Instant.now(clock);
        ContactNoveltyTracker.Pending before = window != null ? novelty.pending(debtor.getId(), now) : null;
        Map<ContactType, Integer> receivedNew = new EnumMap<>(ContactType.class);
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
                ContactRegistry.Person person = person(contact, warnings);
                if (batchId == null) {
                    boolean added = contactRegistry.addIfAbsent(debtor, value, label, whatsapp, ContactSource.IMPORT,
                            null, person).isPresent();
                    contactsAdded |= added || contactRegistry.completePerson(debtor, value, person);
                    continue;
                }
                ContactRegistry.ImportReceipt receipt = contactRegistry.receiveFromImport(debtor, value, label, whatsapp,
                        batchId, afterRegistration, window, now, person);
                contactsAdded |= receipt.created() || contactRegistry.completePerson(debtor, value, person);
                if (receipt.receivedNew()) {
                    receivedNew.merge(type, 1, Integer::sum);
                    metrics.contactReceived("new");
                } else if (receipt.resent()) {
                    metrics.contactReceived("resent");
                }
            } catch (BusinessRuleException e) {
                // contato inválido não impede a carga do devedor: volta como aviso para o relatório de erros
                warnings.add(e.code());
            }
        }
        contactsAdded |= representatives(debtor, record.representatives(), warnings);
        if (!receivedNew.isEmpty()) {
            BusinessEvent event = BusinessEvent.of(BusinessEventTypes.CONTACT_RECEIVED_NEW).debtor(debtor.getId())
                    .reference("import_batch", batchId);
            receivedNew.forEach((type, count) -> event.attr(type.name().toLowerCase(Locale.ROOT), count));
            businessEvents.log(event);
            novelty.publishIfChanged(debtor.getId(), before, novelty.pending(debtor.getId(), now), batchId, now);
        }
        if (outcome == Outcome.UNCHANGED && contactsAdded) {
            outcome = Outcome.UPDATED;
        }
        return new UpsertResult(debtor.getId(), outcome, warnings);
    }

    /** Pessoa de contato da carga; papel inválido vira aviso e o contato segue sem o papel. */
    private static ContactRegistry.Person person(ContactRecord contact, List<String> warnings) {
        String name = contact.personName() == null || contact.personName().isBlank() ? null : contact.personName().trim();
        ContactPersonRole role = null;
        if (contact.personRole() != null && !contact.personRole().isBlank()) {
            try {
                role = DebtViews.parseEnum(ContactPersonRole.class, contact.personRole(), "contact.person_role_invalid",
                        "Papel da pessoa de contato");
            } catch (BusinessRuleException e) {
                warnings.add(e.code());
            }
        }
        if (name != null && name.length() > 120) {
            warnings.add("contact.person_name_too_long");
            name = null;
        }
        return name == null && role == null ? null : new ContactRegistry.Person(name, role, false);
    }

    /**
     * Representantes da empresa vindos da carga (B3). Devedor pessoa física não tem representantes: aviso
     * {@code representative.company_required}. Representante inválido não impede a carga: volta como aviso.
     *
     * @return verdadeiro se algum representante foi criado ou alterado
     */
    private boolean representatives(Debtor debtor, List<RepresentativeRecord> records, List<String> warnings) {
        if (records.isEmpty()) {
            return false;
        }
        if (!debtor.isCompany()) {
            warnings.add("representative.company_required");
            return false;
        }
        boolean changed = false;
        for (RepresentativeRecord r : records) {
            try {
                Document cpf = Document.parse(r.document());
                if (cpf.type() != Document.DocumentType.CPF) {
                    throw new BusinessRuleException("representative.cpf_required", "Representante deve ter CPF");
                }
                // papel ou poder nulo = não informado na carga: o registro mantém o valor cadastrado
                RepresentativeRole role = r.role() == null ? null : CompanyRepresentativeService.role(r.role());
                changed |= representativeRegistry.upsertFromImport(debtor, cpf, r.name(), role, r.canNegotiate()).changed();
            } catch (BusinessRuleException e) {
                warnings.add(e.code().startsWith("representative.") ? e.code() : "representative.invalid");
            }
        }
        return changed;
    }
}
