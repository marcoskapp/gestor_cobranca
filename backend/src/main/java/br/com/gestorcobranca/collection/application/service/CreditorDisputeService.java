package br.com.gestorcobranca.collection.application.service;

import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.CreditorResponseCommand;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.DisputedTitle;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.EvidenceUpload;
import br.com.gestorcobranca.collection.application.port.in.creditor.CreditorDisputeUseCases;
import br.com.gestorcobranca.collection.domain.model.CreditorRequestStatus;
import br.com.gestorcobranca.collection.domain.model.CreditorResponseSource;
import br.com.gestorcobranca.collection.domain.model.Dispute;
import br.com.gestorcobranca.collection.domain.model.DisputeCreditorRequest;
import br.com.gestorcobranca.collection.domain.model.DisputeCreditorResponse;
import br.com.gestorcobranca.collection.domain.model.DisputeDocumentQuarantine;
import br.com.gestorcobranca.collection.domain.model.DisputeEvidence;
import br.com.gestorcobranca.collection.domain.model.DisputeStatus;
import br.com.gestorcobranca.collection.domain.repository.DisputeCreditorRequestRepository;
import br.com.gestorcobranca.collection.domain.repository.DisputeCreditorResponseRepository;
import br.com.gestorcobranca.collection.domain.repository.DisputeDocumentQuarantineRepository;
import br.com.gestorcobranca.collection.domain.repository.DisputeEvidenceRepository;
import br.com.gestorcobranca.collection.domain.repository.DisputeRepository;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.shared.storage.FileCategory;
import br.com.gestorcobranca.shared.storage.FileStorage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Contestações no portal do credor (ADR 0011, fatia 5). Mostra só o que o credor precisa para responder ao pedido da
 * assessoria — protocolo, natureza, títulos (número no credor, vencimento e saldo), valor contestado, o pedido como foi
 * enviado e os documentos dele — nunca o relato do devedor, o responsável, as notas internas nem o resumo que a
 * assessoria registrou de respostas recebidas por outro canal. A resposta usa o mesmo canal da assessoria
 * ({@link DisputeCreditorChannel#respond}) com a origem {@code CREDITOR_PORTAL}: instante do servidor, documentos pela
 * quarentena, pedido marcado como respondido e a contestação continua aberta.
 */
@Service
@Transactional
class CreditorDisputeService implements CreditorDisputeUseCases {

    private static final String RESOURCE = "dispute";
    /** Teto de proteção dos pedidos que aguardam o credor (todos entram na lista). */
    static final int AWAITING_LIMIT = 2000;
    /** Pedidos mais recentes considerados para as respondidas e encerradas. */
    static final int RECENT_LIMIT = 300;
    private static final Set<CreditorRequestStatus> AWAITING = Set.of(CreditorRequestStatus.QUEUED,
            CreditorRequestStatus.SENT, CreditorRequestStatus.FAILED);

    private final DisputeRepository disputes;
    private final DisputeCreditorRequestRepository requests;
    private final DisputeCreditorResponseRepository responses;
    private final DisputeEvidenceRepository evidences;
    private final DisputeDocumentQuarantineRepository quarantine;
    private final DisputeViews views;
    private final DisputeCreditorChannel channel;
    private final FileStorage fileStorage;
    private final AccessScope accessScope;
    private final CurrentActor currentActor;
    private final AccessLogger accessLogger;
    private final Clock clock;

    CreditorDisputeService(DisputeRepository disputes, DisputeCreditorRequestRepository requests,
                           DisputeCreditorResponseRepository responses, DisputeEvidenceRepository evidences,
                           DisputeDocumentQuarantineRepository quarantine, DisputeViews views,
                           DisputeCreditorChannel channel, FileStorage fileStorage, AccessScope accessScope,
                           CurrentActor currentActor, AccessLogger accessLogger, Clock clock) {
        this.disputes = disputes;
        this.requests = requests;
        this.responses = responses;
        this.evidences = evidences;
        this.quarantine = quarantine;
        this.views = views;
        this.channel = channel;
        this.fileStorage = fileStorage;
        this.accessScope = accessScope;
        this.currentActor = currentActor;
        this.accessLogger = accessLogger;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<CreditorDisputeSummary> disputes() {
        UUID creditorId = creditor(Permission.CREDITOR_PORTAL_OVERVIEW);
        // todas as que aguardam o credor (com um teto só de proteção) e as mais recentes já respondidas ou encerradas;
        // o resumo de cada contestação usa todos os pedidos dela, nunca só os que entraram no corte
        Set<UUID> disputeIds = new LinkedHashSet<>();
        requests.findAwaitingByCreditorId(creditorId, AWAITING_LIMIT).forEach(r -> disputeIds.add(r.getDisputeId()));
        requests.findRecentByCreditorId(creditorId, RECENT_LIMIT).forEach(r -> disputeIds.add(r.getDisputeId()));
        if (disputeIds.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<DisputeCreditorRequest>> byDispute = requests.findByDisputeIdIn(disputeIds).stream()
                .collect(Collectors.groupingBy(DisputeCreditorRequest::getDisputeId));
        Map<UUID, List<DisputeCreditorResponse>> answers = responses.findByDisputeIdIn(byDispute.keySet()).stream()
                .collect(Collectors.groupingBy(DisputeCreditorResponse::getDisputeId));
        LocalDate today = CollectionViews.today(clock);
        return disputes.findByIdIn(byDispute.keySet()).stream()
                .filter(d -> d.getCreditorId().equals(creditorId))
                .map(d -> summary(d, byDispute.getOrDefault(d.getId(), List.of()), answers.getOrDefault(d.getId(), List.of()), today))
                .sorted(LIST_ORDER).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public CreditorDisputeDetail dispute(UUID disputeId) {
        UUID creditorId = creditor(Permission.CREDITOR_PORTAL_OVERVIEW);
        Dispute d = load(disputeId, creditorId);
        return detail(d);
    }

    @Override
    @Transactional(readOnly = true)
    public CreditorDocumentContent download(UUID disputeId, UUID documentId) {
        UUID creditorId = creditor(Permission.CREDITOR_PORTAL_OVERVIEW);
        Dispute d = load(disputeId, creditorId);
        DisputeEvidence evidence = evidences.findById(documentId)
                .filter(e -> e.getDisputeId().equals(d.getId()))
                .filter(e -> shownToCreditor(d).contains(e.getId()))
                .orElseThrow(() -> ResourceNotFoundException.of("dispute_evidence", documentId));
        accessLogger.log(AccessEvent.of(AccessEventType.FILE_DOWNLOADED, currentActor.get(),
                Map.of("resource", "dispute_evidence", "resourceId", documentId.toString(),
                        "category", FileCategory.EVIDENCE.name(), "origin", "creditor_portal")));
        return new CreditorDocumentContent(evidence.getFileName(), evidence.getContentType(), evidence.getSize(),
                evidence.getSha256(), fileStorage.open(evidence.getFileKey()));
    }

    @Override
    public CreditorDisputeDetail respond(UUID disputeId, CreditorDisputeResponseCommand command,
                                         List<CreditorDocumentUpload> documents) {
        UUID creditorId = creditor(Permission.CREDITOR_DISPUTE_RESPOND);
        Guard.require(command != null && command.requestId() != null, "dispute_response.request_required",
                "Informe o pedido que está respondendo");
        Dispute d = load(disputeId, creditorId);
        DisputeCreditorRequest request = requests.findByDisputeIdOrderBySequenceNumberAsc(d.getId()).stream()
                .filter(r -> r.getId().equals(command.requestId())).findFirst()
                .orElseThrow(() -> new BusinessRuleException("dispute_response.request_invalid",
                        "Pedido não pertence à contestação"));
        Guard.requireState(d.getStatus().isOpen(), "dispute.not_open", "A contestação já foi encerrada");
        Guard.requireState(AWAITING.contains(request.getStatus()), "dispute_response.not_awaiting",
                "Este pedido já foi respondido ou cancelado");
        List<EvidenceUpload> uploads = documents == null ? List.of() : documents.stream()
                .map(f -> new EvidenceUpload(f.fileName(), f.contentType(), f.size(), f.content())).toList();
        channel.respond(d, new CreditorResponseCommand(command.position(), command.note(), request.getId(), null),
                uploads, CreditorResponseSource.CREDITOR_PORTAL);
        return detail(d);
    }

    // ------------------------------------------------------------------ apoio

    private UUID creditor(Permission permission) {
        Actor actor = currentActor.get();
        if (!actor.isCreditor()) {
            throw accessScope.denyActorType(RESOURCE);
        }
        accessScope.requirePermission(permission);
        return actor.creditorScope().creditorIds().iterator().next();
    }

    /** Contestação do credor com ao menos um pedido a ele; outro credor → 404 com {@code SCOPE_DENIED} (RS-06). */
    private Dispute load(UUID disputeId, UUID creditorId) {
        Dispute d = disputes.findById(disputeId).orElseThrow(() -> ResourceNotFoundException.of(RESOURCE, disputeId));
        accessScope.requireVisible(RESOURCE, disputeId, d.getCreditorId());
        if (!d.getCreditorId().equals(creditorId) || requests.countByDisputeId(d.getId()) == 0) {
            throw ResourceNotFoundException.of(RESOURCE, disputeId);
        }
        return d;
    }

    private CreditorDisputeDetail detail(Dispute d) {
        List<DisputeCreditorRequest> ofDispute = requests.findByDisputeIdOrderBySequenceNumberAsc(d.getId());
        List<DisputeCreditorResponse> answers = responses.findByDisputeIdOrderByReceivedAtAsc(d.getId());
        Map<UUID, DisputeEvidence> evidenceById = evidences.findByDisputeIdOrderByCreatedAtAsc(d.getId()).stream()
                .collect(Collectors.toMap(DisputeEvidence::getId, e -> e, (a, b) -> a, LinkedHashMap::new));
        List<DisputeDocumentQuarantine> quarantined = quarantine.findByDisputeId(d.getId()).stream()
                .filter(q -> q.getCreditorResponseId() != null).toList();
        LocalDate today = CollectionViews.today(clock);
        List<CreditorDisputedTitle> titles = views.titles(d).stream()
                .map(CreditorDisputeService::title).toList();
        List<CreditorDisputeRequest> requestItems = ofDispute.stream().map(r -> new CreditorDisputeRequest(r.getId(),
                r.getSequenceNumber(), r.getSentAt(), r.getRequestedDueDate(), AWAITING.contains(r.getStatus()),
                AWAITING.contains(r.getStatus()) && r.getRequestedDueDate().isBefore(today), withoutSecureLink(r.getSubject()),
                withoutSecureLink(r.getBody()), r.getEvidenceIds().stream().map(evidenceById::get).filter(Objects::nonNull)
                .map(CreditorDisputeService::document).toList())).toList();
        List<CreditorDisputeResponse> responseItems = answers.stream().map(r -> new CreditorDisputeResponse(r.getId(),
                r.getRequestId(), r.getPosition().name(), origin(r.getSource()),
                r.getSource() == CreditorResponseSource.CREDITOR_PORTAL ? r.getNote() : null, r.getReceivedAt(),
                evidenceById.values().stream().filter(e -> r.getId().equals(e.getCreditorResponseId()))
                        .map(CreditorDisputeService::document).toList(),
                (int) quarantined.stream().filter(q -> r.getId().equals(q.getCreditorResponseId()))
                        .filter(DisputeDocumentQuarantine::isPending).count(),
                (int) quarantined.stream().filter(q -> r.getId().equals(q.getCreditorResponseId()))
                        .filter(q -> q.getStatus() == DisputeDocumentQuarantine.Status.INFECTED
                                || q.getStatus() == DisputeDocumentQuarantine.Status.FAILED).count())).toList();
        return new CreditorDisputeDetail(summary(d, ofDispute, answers, today), titles, requestItems, responseItems);
    }

    /** Documentos que o credor pode baixar: os enviados nos pedidos e os trazidos pelas respostas do credor. */
    private Set<UUID> shownToCreditor(Dispute d) {
        Set<UUID> ids = new HashSet<>();
        requests.findByDisputeIdOrderBySequenceNumberAsc(d.getId()).forEach(r -> ids.addAll(r.getEvidenceIds()));
        evidences.findByDisputeIdOrderByCreatedAtAsc(d.getId()).stream()
                .filter(e -> e.getCreditorResponseId() != null).forEach(e -> ids.add(e.getId()));
        return ids;
    }

    private static CreditorDisputeSummary summary(Dispute d, List<DisputeCreditorRequest> ofDispute,
                                                  List<DisputeCreditorResponse> answers, LocalDate today) {
        boolean open = d.getStatus().isOpen();
        List<DisputeCreditorRequest> awaiting = open
                ? ofDispute.stream().filter(r -> AWAITING.contains(r.getStatus())).toList() : List.of();
        LocalDate due = !awaiting.isEmpty()
                ? awaiting.stream().map(DisputeCreditorRequest::getRequestedDueDate).min(Comparator.naturalOrder()).orElseThrow()
                : ofDispute.stream().max(Comparator.comparingInt(DisputeCreditorRequest::getSequenceNumber))
                        .map(DisputeCreditorRequest::getRequestedDueDate).orElse(null);
        String situation = !open ? "CLOSED" : awaiting.isEmpty() ? "ANSWERED" : "AWAITING_YOU";
        String outcome = open ? null : d.getStatus() == DisputeStatus.WITHDRAWN ? "WITHDRAWN"
                : d.getOutcome() == null ? null : d.getOutcome().name();
        Instant lastResponse = answers.stream().map(DisputeCreditorResponse::getReceivedAt)
                .max(Comparator.naturalOrder()).orElse(null);
        return new CreditorDisputeSummary(d.getId(), DisputeTexts.protocol(d.getId()), d.getType().name(), situation,
                outcome, d.getOpenedAt(), CollectionViews.amount(d.getDisputedAmount()), d.getTitleIds().size(), due,
                !awaiting.isEmpty() && due.isBefore(today), lastResponse, open ? null : d.getResolvedAt());
    }

    /** Aguardando primeiro (prazo mais próximo), depois respondidas e encerradas (mais recentes primeiro). */
    private static final Comparator<CreditorDisputeSummary> LIST_ORDER = Comparator
            .comparingInt((CreditorDisputeSummary s) -> switch (s.situation()) {
                case "AWAITING_YOU" -> 0;
                case "ANSWERED" -> 1;
                default -> 2;
            })
            .thenComparing(s -> s.situation().equals("AWAITING_YOU") ? s.requestedDueDate() : null,
                    Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(CreditorDisputeSummary::openedAt, Comparator.reverseOrder());

    /**
     * O assunto e o texto do pedido como foram enviados, sem o link seguro das evidências: o token é um segredo do e-mail, e no portal
     * os documentos do pedido estão na própria ficha.
     */
    static String withoutSecureLink(String body) {
        return body == null ? null : SECURE_LINK.matcher(body).replaceAll("os documentos estão na ficha desta contestação, no portal");
    }

    private static final java.util.regex.Pattern SECURE_LINK = java.util.regex.Pattern.compile(
            "\\S*" + java.util.regex.Pattern.quote(DisputeCreditorChannel.LINK_PATH) + "[A-Za-z0-9_-]+");

    private static String origin(CreditorResponseSource source) {
        return switch (source) {
            case CREDITOR_PORTAL -> "CREDITOR_PORTAL";
            case PUBLIC_API -> "INTEGRATION";
            case MANUAL, EMAIL -> "AGENCY";
        };
    }

    private static CreditorDisputedTitle title(DisputedTitle t) {
        return new CreditorDisputedTitle(t.externalId(), t.installmentNumber(), t.dueDate(), t.openBalance());
    }

    private static CreditorDisputeDocument document(DisputeEvidence e) {
        return new CreditorDisputeDocument(e.getId(), e.getFileName(), e.getSize(), e.getSha256());
    }
}
