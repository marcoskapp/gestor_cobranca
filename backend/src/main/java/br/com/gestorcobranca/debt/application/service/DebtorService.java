package br.com.gestorcobranca.debt.application.service;

import br.com.gestorcobranca.debt.application.port.in.DebtorUseCases;
import br.com.gestorcobranca.debt.domain.model.Debtor;
import br.com.gestorcobranca.debt.domain.model.DebtorContact;
import br.com.gestorcobranca.debt.domain.repository.DebtTitleRepository;
import br.com.gestorcobranca.debt.domain.repository.DebtorContactRepository;
import br.com.gestorcobranca.debt.domain.repository.DebtorRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Document;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.security.Permission;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
class DebtorService implements DebtorUseCases {

    private static final int MAX_INACTIVE_BATCH = 1_000;

    private final DebtorRepository debtors;
    private final DebtorContactRepository contacts;
    private final DebtTitleRepository titles;
    private final DebtorVisibility visibility;
    private final PersonalDataIndex index;
    private final AccessScope accessScope;
    private final CurrentActor currentActor;
    private final AuditTrail auditTrail;
    private final AccessLogger accessLogger;
    private final Clock clock;

    DebtorService(DebtorRepository debtors, DebtorContactRepository contacts, DebtTitleRepository titles,
                  DebtorVisibility visibility, PersonalDataIndex index, AccessScope accessScope, CurrentActor currentActor,
                  AuditTrail auditTrail, AccessLogger accessLogger, Clock clock) {
        this.debtors = debtors;
        this.contacts = contacts;
        this.titles = titles;
        this.visibility = visibility;
        this.index = index;
        this.accessScope = accessScope;
        this.currentActor = currentActor;
        this.auditTrail = auditTrail;
        this.accessLogger = accessLogger;
        this.clock = clock;
    }

    @Override
    public DebtorView register(RegisterDebtorCommand command) {
        Document document = Document.parse(command.document());
        String hash = index.document(document);
        if (debtors.findByDocumentHash(hash).isPresent()) {
            throw new ConflictException("debtor.document_taken", "Já existe devedor com este documento");
        }
        Debtor debtor = debtors.save(new Debtor(document, hash, command.name(), command.birthDate(), command.notes()));
        auditTrail.record(AuditEntry.of("debtor.created", "debtor", debtor.getId())
                .created("personType", debtor.getPersonType())
                .created("document", document.masked())
                .created("name", DebtViews.maskName(debtor.getName()))
                .created("birthDate", DebtViews.maskDate(debtor.getBirthDate()))
                .meta("source", "MANUAL"));
        return DebtViews.debtor(debtor);
    }

    @Override
    public DebtorView update(UUID debtorId, UpdateDebtorCommand command) {
        Debtor debtor = visibility.requireVisible(debtorId);
        String previousName = debtor.getName();
        LocalDate previousBirthDate = debtor.getBirthDate();
        String previousNotes = debtor.getNotes();
        debtor.update(command.name(), command.birthDate(), command.notes());
        debtors.save(debtor);
        // a trilha é imutável e sobrevive à anonimização: nome mascarado, nascimento e notas só indicados
        AuditEntry audit = AuditEntry.of("debtor.updated", "debtor", debtorId);
        if (!previousName.equals(debtor.getName())) {
            audit.change("name", DebtViews.maskName(previousName), DebtViews.maskName(debtor.getName()));
        }
        if (!Objects.equals(previousBirthDate, debtor.getBirthDate())) {
            audit.meta("birthDateChanged", true);
        }
        if (!Objects.equals(previousNotes, debtor.getNotes())) {
            audit.meta("notesChanged", true);
        }
        auditTrail.record(audit);
        return DebtViews.debtor(debtor);
    }

    @Override
    @Transactional(readOnly = true)
    public DebtorView get(UUID debtorId) {
        return DebtViews.debtor(visibility.requireVisible(debtorId));
    }

    @Override
    @Transactional(readOnly = true)
    public RevealedDocument revealDocument(UUID debtorId) {
        accessScope.requirePermission(Permission.DEBTOR_SENSITIVE_VIEW);
        Debtor debtor = visibility.requireVisible(debtorId);
        Document document = debtor.document()
                .orElseThrow(() -> new BusinessRuleException("debtor.anonymized", "Devedor anonimizado não possui documento"));
        accessLogger.log(AccessEvent.of(AccessEventType.SENSITIVE_DATA_VIEWED, currentActor.get(),
                Map.of("resource", "debtor", "resourceId", debtorId.toString(), "field", "document")));
        return new RevealedDocument(debtorId, document.type().name(), document.formatted());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DebtorView> findByDocument(String document) {
        String hash = index.document(Document.parse(document));
        return debtors.findByDocumentHash(hash).flatMap(visibility::findVisible).map(DebtViews::debtor);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<DebtorView> search(String text, UUID creditorId, UUID portfolioId, PageQuery page) {
        String documentHash = documentHashIfDocument(text);
        return PageResponse.of(debtors.search(visibility.allowedCreditors(), creditorId, portfolioId, text, documentHash,
                page.toPageable(Sort.by("name", "id"))), DebtViews::debtor);
    }

    /** Texto com 11 ou 14 dígitos válidos como CPF/CNPJ é buscado pelo blind index, não pelo nome. */
    private String documentHashIfDocument(String text) {
        if (text == null) {
            return null;
        }
        String digits = text.replaceAll("\\D", "");
        boolean looksLikeDocument = text.trim().matches("[\\d.\\-/\\s]+")
                && (digits.length() == 11 && Document.isValid(Document.DocumentType.CPF, digits)
                || digits.length() == 14 && Document.isValid(Document.DocumentType.CNPJ, digits));
        return looksLikeDocument ? index.document(Document.parse(digits)) : null;
    }

    @Override
    public DebtorView anonymize(UUID debtorId, String reason) {
        accessScope.requirePermission(Permission.DATA_SUBJECT_REQUEST_MANAGE);
        if (reason == null || reason.isBlank()) {
            throw new BusinessRuleException("debtor.anonymize_reason_required", "Informe o motivo da anonimização");
        }
        Debtor debtor = visibility.requireFullScope(debtorId);
        if (titles.existsOpenByDebtorId(debtorId)) {
            throw new ConflictException("debtor.has_open_titles",
                    "Devedor possui títulos em cobrança; encerre-os antes de anonimizar");
        }
        String maskedDocument = debtor.maskedDocument();
        List<DebtorContact> removed = contacts.findByDebtorIdOrderByTypeAscPriorityAscCreatedAtAsc(debtorId);
        debtor.anonymize(Instant.now(clock));
        contacts.deleteAll(removed);
        debtors.save(debtor);
        auditTrail.record(AuditEntry.of("debtor.anonymized", "debtor", debtorId)
                .reason(reason)
                .change("status", "ACTIVE", debtor.getStatus())
                .change("document", maskedDocument, null)
                .meta("contactsRemoved", removed.size()));
        return DebtViews.debtor(debtor);
    }

    @Override
    @Transactional(readOnly = true)
    public List<InactiveDebtor> findInactiveDebtors(Instant closedBefore, UUID afterId, int limit) {
        accessScope.requirePermission(Permission.RETENTION_MANAGE);
        accessScope.requireAllCreditors("debtor");
        if (closedBefore == null || limit < 1 || limit > MAX_INACTIVE_BATCH) {
            throw new BusinessRuleException("debtor.inactive_query_invalid",
                    "Informe a data de corte e um lote entre 1 e %d".formatted(MAX_INACTIVE_BATCH));
        }
        return debtors.findInactive(closedBefore, afterId, limit).stream()
                .map(c -> new InactiveDebtor(c.debtorId(), c.lastActivityAt(), titles.findCreditorIdsByDebtorId(c.debtorId())))
                .toList();
    }
}
