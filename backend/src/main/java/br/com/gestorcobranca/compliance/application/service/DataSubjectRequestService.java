package br.com.gestorcobranca.compliance.application.service;

import br.com.gestorcobranca.compliance.application.ComplianceProperties;
import br.com.gestorcobranca.compliance.application.port.in.FileContent;
import br.com.gestorcobranca.compliance.application.port.in.DataSubjectRequestUseCases;
import br.com.gestorcobranca.compliance.domain.model.ContactRestriction;
import br.com.gestorcobranca.compliance.domain.model.DataSubjectRequest;
import br.com.gestorcobranca.compliance.domain.model.LegalBasisRecord;
import br.com.gestorcobranca.compliance.domain.model.RequestChannel;
import br.com.gestorcobranca.compliance.domain.model.RequestStatus;
import br.com.gestorcobranca.compliance.domain.model.RequestType;
import br.com.gestorcobranca.compliance.domain.model.RestrictionSource;
import br.com.gestorcobranca.compliance.domain.model.RestrictionType;
import br.com.gestorcobranca.compliance.domain.repository.DataSubjectRequestRepository;
import br.com.gestorcobranca.compliance.domain.repository.LegalBasisRecordRepository;
import br.com.gestorcobranca.compliance.domain.service.RequestDeadlines;
import br.com.gestorcobranca.debt.application.port.in.DebtTitleUseCases;
import br.com.gestorcobranca.debt.application.port.in.DebtTitleUseCases.TitleView;
import br.com.gestorcobranca.debt.application.port.in.DebtorUseCases;
import br.com.gestorcobranca.debt.application.port.in.DebtorUseCases.DebtorView;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.crypto.BlindIndex;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Document;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.logging.SensitiveDataMasker;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.storage.FileCategory;
import br.com.gestorcobranca.shared.storage.FileStorage;
import br.com.gestorcobranca.shared.storage.FileStorage.StoredFile;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional
class DataSubjectRequestService implements DataSubjectRequestUseCases {

    /** Status de título em cobrança: enquanto houver, o debt recusa a anonimização. */
    private static final Set<String> TITLE_IN_COLLECTION = Set.of("OPEN", "SUSPENDED");
    private static final Duration FUTURE_TOLERANCE = Duration.ofMinutes(1);
    private static final String RESOURCE = "data_subject_request";

    private final DataSubjectRequestRepository requests;
    private final LegalBasisRecordRepository legalBases;
    private final DataSubjectExportBuilder exportBuilder;
    private final RestrictionRecorder restrictionRecorder;
    private final DebtorUseCases debtors;
    private final DebtTitleUseCases titles;
    private final BlindIndex blindIndex;
    private final AccessScope accessScope;
    private final CurrentActor currentActor;
    private final AuditTrail auditTrail;
    private final AccessLogger accessLogger;
    private final BusinessEventLogger businessEvents;
    private final FileStorage fileStorage;
    private final StoredFileCleanup fileCleanup;
    private final RequestDeadlines deadlines;
    private final Clock clock;

    DataSubjectRequestService(DataSubjectRequestRepository requests, LegalBasisRecordRepository legalBases,
                              DataSubjectExportBuilder exportBuilder, RestrictionRecorder restrictionRecorder,
                              DebtorUseCases debtors, DebtTitleUseCases titles, BlindIndex blindIndex,
                              AccessScope accessScope, CurrentActor currentActor, AuditTrail auditTrail,
                              AccessLogger accessLogger, BusinessEventLogger businessEvents, FileStorage fileStorage,
                              StoredFileCleanup fileCleanup, ComplianceProperties properties, Clock clock) {
        this.requests = requests;
        this.legalBases = legalBases;
        this.exportBuilder = exportBuilder;
        this.restrictionRecorder = restrictionRecorder;
        this.debtors = debtors;
        this.titles = titles;
        this.blindIndex = blindIndex;
        this.accessScope = accessScope;
        this.currentActor = currentActor;
        this.auditTrail = auditTrail;
        this.accessLogger = accessLogger;
        this.businessEvents = businessEvents;
        this.fileStorage = fileStorage;
        this.fileCleanup = fileCleanup;
        this.deadlines = new RequestDeadlines(properties.dataSubjectRequest().dueDays(),
                properties.dataSubjectRequest().defaultDueDays(), TimeConfig.BUSINESS_ZONE);
        this.clock = clock;
    }

    @Override
    public RequestView open(OpenRequestCommand command) {
        accessScope.requireAllCreditors(RESOURCE);
        RequestType type = ComplianceEnums.parse(RequestType.class, command.type(), "dsr.type_invalid", "Tipo da solicitação");
        RequestChannel channel = ComplianceEnums.parse(RequestChannel.class, command.channel(), "dsr.channel_invalid",
                "Canal de recebimento");
        Instant now = Instant.now(clock);
        Instant receivedAt = command.receivedAt() == null ? now : command.receivedAt();
        Guard.require(!receivedAt.isAfter(now.plus(FUTURE_TOLERANCE)), "dsr.received_at_future",
                "Data de recebimento não pode estar no futuro");
        String document = command.document() == null || command.document().isBlank() ? null
                : Document.parse(command.document()).number();
        DataSubjectRequest request = requests.save(new DataSubjectRequest(type, channel, command.requesterName(), document,
                documentHash(document), command.requesterContact(), command.details(), receivedAt,
                deadlines.dueAt(type, receivedAt)));
        auditTrail.record(AuditEntry.of("data_subject_request.opened", RESOURCE, request.getId())
                .created("type", type)
                .created("channel", channel)
                .created("receivedAt", receivedAt)
                .created("dueAt", request.getDueAt())
                .created("document", maskedDocument(request)));
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.DATA_SUBJECT_REQUEST_OPENED)
                .reference(RESOURCE, request.getId())
                .channel(channel.name())
                .attr("type", type)
                .attr("dueDays", deadlines.daysFor(type)));
        return view(request);
    }

    @Override
    public RequestView verifyIdentity(UUID requestId, UUID debtorId) {
        DataSubjectRequest request = loadForCommand(requestId);
        UUID resolved = resolveDebtor(request, debtorId);
        RequestStatus previous = request.getStatus();
        request.verifyIdentity(resolved, currentActor.actorId(), Instant.now(clock));
        requests.save(request);
        auditTrail.record(AuditEntry.of("data_subject_request.identity_verified", RESOURCE, requestId)
                .change("status", previous, request.getStatus())
                .change("debtorId", null, resolved));
        return view(request);
    }

    /** Devedor do titular: informado (conferido com o documento) ou localizado pelo documento. */
    private UUID resolveDebtor(DataSubjectRequest request, UUID debtorId) {
        Optional<DebtorView> byDocument = request.getDocument() == null ? Optional.empty()
                : debtors.findByDocument(request.getDocument());
        if (debtorId == null) {
            if (byDocument.isEmpty() && request.getType().requiresDebtor()) {
                throw new BusinessRuleException("dsr.debtor_not_found",
                        "Titular não localizado na base pelo documento informado");
            }
            return byDocument.map(DebtorView::id).orElse(null);
        }
        debtors.get(debtorId);
        if (request.getDocument() != null && byDocument.map(DebtorView::id).filter(debtorId::equals).isEmpty()) {
            throw new ConflictException("dsr.document_mismatch", "O documento do requerente não é o do devedor informado");
        }
        return debtorId;
    }

    @Override
    public RequestView start(UUID requestId, UUID assignee) {
        DataSubjectRequest request = loadForCommand(requestId);
        RequestStatus previous = request.getStatus();
        UUID previousAssignee = request.getAssignedTo();
        request.start(assignee);
        requests.save(request);
        auditTrail.record(AuditEntry.of("data_subject_request.started", RESOURCE, requestId)
                .change("status", previous, request.getStatus())
                .change("assignedTo", previousAssignee, request.getAssignedTo()));
        return view(request);
    }

    @Override
    public RequestView assign(UUID requestId, UUID assignee) {
        DataSubjectRequest request = loadForCommand(requestId);
        UUID previous = request.getAssignedTo();
        request.assign(assignee);
        requests.save(request);
        auditTrail.record(AuditEntry.of("data_subject_request.assigned", RESOURCE, requestId)
                .change("assignedTo", previous, assignee));
        return view(request);
    }

    @Override
    public RequestView execute(UUID requestId) {
        DataSubjectRequest request = loadForCommand(requestId);
        request.requireExecutable();
        Instant now = Instant.now(clock);
        switch (request.getType()) {
            case ACCESS, PORTABILITY -> exportData(request, now);
            case ANONYMIZATION -> anonymize(request, now);
            case OPPOSITION -> oppose(request, now);
            default -> throw new IllegalStateException("Tipo sem execução automática: " + request.getType());
        }
        requests.save(request);
        return view(request);
    }

    private void exportData(DataSubjectRequest request, Instant now) {
        byte[] content = exportBuilder.build(request, now);
        StoredFile stored = fileStorage.store(FileCategory.DATA_SUBJECT_EXPORT,
                "dados-titular-%s.json".formatted(request.getId()), "application/json",
                new ByteArrayInputStream(content), content.length);
        fileCleanup.deleteOnRollback(stored.key());
        request.attachResult(stored.key(), stored.sha256(), now);
        accessLogger.log(AccessEvent.of(AccessEventType.DATA_EXPORTED, currentActor.get(),
                Map.of("resource", "debtor", "resourceId", request.getDebtorId().toString(),
                        "export", "data_subject_" + request.getType().name().toLowerCase(),
                        "requestId", request.getId().toString(), "sha256", stored.sha256())));
        auditTrail.record(AuditEntry.of("data_subject_request.data_exported", RESOURCE, request.getId())
                .meta("debtorId", request.getDebtorId())
                .meta("sha256", stored.sha256())
                .meta("size", stored.size()));
    }

    /**
     * A anonimização é recusada pelo debt enquanto houver título em cobrança. A condição é
     * verificada antes de chamar o debt para que a recusa seja registrada na solicitação com
     * fundamentação (uma exceção vinda do debt desfaria a transação inteira). Se a situação
     * mudar entre a verificação e a chamada, o debt ainda recusa e nada é gravado.
     */
    private void anonymize(DataSubjectRequest request, Instant now) {
        UUID debtorId = request.getDebtorId();
        List<TitleView> inCollection = titles.listByDebtor(debtorId).stream()
                .filter(t -> TITLE_IN_COLLECTION.contains(t.status()))
                .toList();
        if (!inCollection.isEmpty()) {
            reject(request, refusalJustification(inCollection, now), now, "open_titles");
            return;
        }
        debtors.anonymize(debtorId, "Solicitação de titular %s (LGPD art. 18)".formatted(request.getId()));
        request.markExecuted(now);
        auditTrail.record(AuditEntry.of("data_subject_request.anonymized", RESOURCE, request.getId())
                .meta("debtorId", debtorId));
    }

    private String refusalJustification(List<TitleView> inCollection, Instant now) {
        Set<UUID> creditorIds = inCollection.stream().map(TitleView::creditorId).collect(Collectors.toSet());
        LocalDate today = LocalDate.ofInstant(now, TimeConfig.BUSINESS_ZONE);
        String bases = legalBases.findByCreditorIdIn(creditorIds).stream()
                .filter(r -> r.isEffectiveOn(today))
                .map(LegalBasisRecord::getLegalBasis).map(Enum::name).distinct().sorted()
                .collect(Collectors.joining(", "));
        return ("Anonimização não realizada: o titular possui %d título(s) em cobrança em %d credor(es). "
                + "Os dados permanecem necessários à cobrança da dívida e ao exercício regular de direitos "
                + "(LGPD art. 7º, V, VI e X; art. 16)%s. A eliminação poderá ser solicitada novamente após o "
                + "encerramento dos títulos.").formatted(inCollection.size(), creditorIds.size(),
                bases.isEmpty() ? "" : "; bases legais registradas: " + bases);
    }

    private void oppose(DataSubjectRequest request, Instant now) {
        ContactRestriction restriction = restrictionRecorder.created(new ContactRestriction(request.getDebtorId(), null, null,
                RestrictionType.DEBTOR_REQUEST, null,
                "Oposição do titular ao contato (solicitação %s)".formatted(request.getId()), RestrictionSource.DEBTOR, now,
                null, request.getId()));
        request.markExecuted(now);
        auditTrail.record(AuditEntry.of("data_subject_request.opposition_applied", RESOURCE, request.getId())
                .meta("debtorId", request.getDebtorId())
                .meta("restrictionId", restriction.getId()));
    }

    @Override
    public RequestView complete(UUID requestId, String responseSummary) {
        DataSubjectRequest request = loadForCommand(requestId);
        Instant now = Instant.now(clock);
        RequestStatus previous = request.getStatus();
        request.complete(responseSummary, now);
        requests.save(request);
        auditTrail.record(AuditEntry.of("data_subject_request.completed", RESOURCE, requestId)
                .change("status", previous, request.getStatus())
                .reason(request.getResponseSummary()));
        logClosed(request, now, null);
        return view(request);
    }

    @Override
    public RequestView reject(UUID requestId, String justification) {
        DataSubjectRequest request = loadForCommand(requestId);
        Instant now = Instant.now(clock);
        reject(request, justification, now, "manual");
        requests.save(request);
        return view(request);
    }

    private void reject(DataSubjectRequest request, String justification, Instant now, String cause) {
        RequestStatus previous = request.getStatus();
        request.reject(justification, now);
        auditTrail.record(AuditEntry.of("data_subject_request.rejected", RESOURCE, request.getId())
                .change("status", previous, request.getStatus())
                .reason(request.getResponseSummary())
                .meta("cause", cause));
        logClosed(request, now, cause);
    }

    private void logClosed(DataSubjectRequest request, Instant now, String rejectionCause) {
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.DATA_SUBJECT_REQUEST_COMPLETED)
                .debtor(request.getDebtorId())
                .reference(RESOURCE, request.getId())
                .channel(request.getChannel().name())
                .durationMs(Duration.between(request.getReceivedAt(), now).toMillis())
                .attr("type", request.getType())
                .attr("outcome", request.getStatus())
                .attr("rejectionCause", rejectionCause)
                .attr("withinDeadline", !now.isAfter(request.getDueAt())));
    }

    @Override
    @Transactional(readOnly = true)
    public RequestView get(UUID requestId) {
        return view(loadVisible(requestId));
    }

    @Override
    @Transactional(readOnly = true)
    public FileContent openResult(UUID requestId) {
        DataSubjectRequest request = loadVisible(requestId);
        if (request.getResultFileKey() == null) {
            throw new ResourceNotFoundException("data_subject_request_result", requestId);
        }
        accessLogger.log(AccessEvent.of(AccessEventType.FILE_DOWNLOADED, currentActor.get(),
                Map.of("resource", RESOURCE, "resourceId", requestId.toString(),
                        "category", FileCategory.DATA_SUBJECT_EXPORT.name())));
        return new FileContent("dados-titular-%s.json".formatted(requestId), "application/json", -1,
                fileStorage.open(request.getResultFileKey()));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<RequestView> search(RequestFilter filter, PageQuery page) {
        if (filter.debtorId() != null) {
            debtors.get(filter.debtorId());
        } else {
            accessScope.requireAllCreditors(RESOURCE);
        }
        String document = filter.document() == null || filter.document().isBlank() ? null
                : Document.parse(filter.document()).number();
        return PageResponse.of(requests.search(
                ComplianceEnums.parseOptional(RequestStatus.class, filter.status(), "dsr.status_invalid", "Status"),
                ComplianceEnums.parseOptional(RequestType.class, filter.type(), "dsr.type_invalid", "Tipo"),
                filter.debtorId(), documentHash(document), filter.overdue(), false,
                page.toPageable(Sort.by("dueAt", "id"))), DataSubjectRequestService::view);
    }

    /** Comandos alcançam todos os dados do titular na assessoria: exigem acesso a todos os credores. */
    private DataSubjectRequest loadForCommand(UUID requestId) {
        accessScope.requireAllCreditors(RESOURCE);
        return requests.findById(requestId).orElseThrow(() -> ResourceNotFoundException.of(RESOURCE, requestId));
    }

    /** Leitura: vinculada a devedor visível; sem devedor, só com acesso a todos os credores. */
    private DataSubjectRequest loadVisible(UUID requestId) {
        DataSubjectRequest request = requests.findById(requestId)
                .orElseThrow(() -> ResourceNotFoundException.of(RESOURCE, requestId));
        if (request.getDebtorId() != null) {
            debtors.get(request.getDebtorId());
        } else if (!accessScope.hasAllCreditors()) {
            throw ResourceNotFoundException.of(RESOURCE, requestId);
        }
        return request;
    }

    private String documentHash(String document) {
        return document == null ? null
                : blindIndex.of(currentActor.tenantId(), DataSubjectRequest.DOCUMENT_HASH_PURPOSE, document);
    }

    private static String maskedDocument(DataSubjectRequest r) {
        return r.getDocument() == null ? null : Document.parse(r.getDocument()).masked();
    }

    static RequestView view(DataSubjectRequest r) {
        return new RequestView(r.getId(), r.getType().name(), r.getChannel().name(), r.getStatus().name(), r.getDebtorId(),
                r.getRequesterName(), maskedDocument(r), SensitiveDataMasker.mask(r.getRequesterContact()), r.getDetails(),
                r.getReceivedAt(), r.getDueAt(), r.isOverdue(), r.getAssignedTo(), r.getIdentityVerifiedAt(),
                r.getIdentityVerifiedBy(), r.getExecutedAt(), r.getCompletedAt(), r.getResponseSummary(),
                r.getResultFileKey() != null, r.getResultSha256());
    }
}
