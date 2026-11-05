package br.com.gestorcobranca.collection.application.service;

import br.com.gestorcobranca.collection.application.port.in.DisputeMailReplyUseCases;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.CreditorResponseCommand;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.CreditorResponseView;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.EvidenceContent;
import br.com.gestorcobranca.collection.domain.model.CreditorResponseSource;
import br.com.gestorcobranca.collection.domain.model.Dispute;
import br.com.gestorcobranca.collection.domain.model.DisputeAssignmentMode;
import br.com.gestorcobranca.collection.domain.model.DisputeCreditorRequest;
import br.com.gestorcobranca.collection.domain.model.DisputeCreditorResponse;
import br.com.gestorcobranca.collection.domain.model.DisputeDocumentQuarantine;
import br.com.gestorcobranca.collection.domain.model.DisputeMailReply;
import br.com.gestorcobranca.collection.domain.model.MailReplyStatus;
import br.com.gestorcobranca.collection.domain.repository.DisputeCreditorRequestRepository;
import br.com.gestorcobranca.collection.domain.repository.DisputeDocumentQuarantineRepository;
import br.com.gestorcobranca.collection.domain.repository.DisputeEvidenceRepository;
import br.com.gestorcobranca.collection.domain.repository.DisputeMailReplyRepository;
import br.com.gestorcobranca.collection.domain.service.MailReplyScreening;
import br.com.gestorcobranca.identity.application.port.in.UserDirectory;
import br.com.gestorcobranca.identity.application.port.in.UserDirectory.UserSummary;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.storage.FileCategory;
import br.com.gestorcobranca.shared.storage.FileStorage;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Respostas do credor por e-mail na contestação: lista no pedido, confirmação da posição pelo analista (registra a
 * resposta do credor, origem EMAIL), descarte com motivo, quarentena da gestão (liberar ou descartar) e o original
 * para conferência. Quem trabalha a contestação decide os e-mails aguardando confirmação (assumindo-a se estiver sem
 * responsável); só a gestão ({@code DISPUTE_MANAGE}) decide os da quarentena.
 */
@Service
@Transactional
class DisputeMailReplyService implements DisputeMailReplyUseCases {

    private final DisputeMailReplyRepository replies;
    private final DisputeCreditorRequestRepository requests;
    private final DisputeEvidenceRepository evidences;
    private final DisputeDocumentQuarantineRepository quarantine;
    private final DisputeAccess disputeAccess;
    private final DisputeAssignments assignments;
    private final DisputeCreditorChannel creditorChannel;
    private final DisputeMailReplyIntake intake;
    private final DisputeViews views;
    private final FileStorage fileStorage;
    private final UserDirectory users;
    private final AccessScope accessScope;
    private final CurrentActor currentActor;
    private final AuditTrail auditTrail;
    private final AccessLogger accessLogger;
    private final BusinessEventLogger businessEvents;
    private final CollectionMetrics metrics;
    private final Clock clock;

    DisputeMailReplyService(DisputeMailReplyRepository replies, DisputeCreditorRequestRepository requests,
                            DisputeEvidenceRepository evidences,
                            DisputeDocumentQuarantineRepository quarantine, DisputeAccess disputeAccess,
                            DisputeAssignments assignments, DisputeCreditorChannel creditorChannel,
                            DisputeMailReplyIntake intake, DisputeViews views, FileStorage fileStorage,
                            UserDirectory users, AccessScope accessScope, CurrentActor currentActor,
                            AuditTrail auditTrail, AccessLogger accessLogger, BusinessEventLogger businessEvents,
                            CollectionMetrics metrics, Clock clock) {
        this.replies = replies;
        this.requests = requests;
        this.evidences = evidences;
        this.quarantine = quarantine;
        this.disputeAccess = disputeAccess;
        this.assignments = assignments;
        this.creditorChannel = creditorChannel;
        this.intake = intake;
        this.views = views;
        this.fileStorage = fileStorage;
        this.users = users;
        this.accessScope = accessScope;
        this.currentActor = currentActor;
        this.auditTrail = auditTrail;
        this.accessLogger = accessLogger;
        this.businessEvents = businessEvents;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<MailReplyView> list(UUID disputeId) {
        Dispute d = disputeAccess.visible(disputeId);
        return views(replies.findByDisputeIdOrderByReceivedAtAsc(d.getId()));
    }

    @Override
    public CreditorResponseView confirm(UUID disputeId, UUID replyId, ConfirmCommand command) {
        Dispute d = loadForWork(disputeId);
        DisputeMailReply reply = load(d, replyId);
        br.com.gestorcobranca.shared.domain.Guard.requireState(reply.getStatus() == MailReplyStatus.PENDING_CONFIRMATION,
                "dispute_mail.not_pending", "O e-mail não aguarda confirmação");
        br.com.gestorcobranca.shared.domain.Guard.require(command != null, "dispute_response.required",
                "Dados da resposta obrigatórios");
        DisputeCreditorResponse response = creditorChannel.respond(d, new CreditorResponseCommand(command.position(),
                command.note(), reply.getRequestId(), reply.getReceivedAt()), List.of(), CreditorResponseSource.EMAIL);
        Actor actor = currentActor.get();
        Instant now = Instant.now(clock);
        reply.confirm(response.getId(), actor.id(), name(actor), now);
        replies.save(reply);
        Duration toConfirm = Duration.between(reply.getReceivedAt(), now);
        auditTrail.record(AuditEntry.of("dispute.mail_confirmed", "dispute", d.getId())
                .creditor(d.getCreditorId())
                .meta("mailReplyId", reply.getId())
                .created("responseId", response.getId())
                .created("position", response.getPosition())
                .change("status", MailReplyStatus.PENDING_CONFIRMATION, MailReplyStatus.CONFIRMED));
        businessEvents.log(DisputeEvents.base(BusinessEventTypes.DISPUTE_CREDITOR_MAIL_CONFIRMED, d)
                .attr("position", response.getPosition())
                .attr("released", reply.getReleasedAt() != null)
                .attr("evidences", evidenceIds(reply.getId()).size())
                .attr("hoursToConfirmation", toConfirm.toHours())
                .durationMs(Math.max(0, toConfirm.toMillis())));
        metrics.disputeMailDecision("confirmed");
        metrics.disputeMailConfirmationTime(toConfirm.isNegative() ? Duration.ZERO : toConfirm);
        return views.creditorResponses(List.of(response), evidences.findByDisputeIdOrderByCreatedAtAsc(d.getId()))
                .getFirst();
    }

    @Override
    public MailReplyView dismiss(UUID disputeId, UUID replyId, String note) {
        Dispute d = disputeAccess.forCommand(disputeId);
        DisputeMailReply reply = load(d, replyId);
        MailReplyStatus before = reply.getStatus();
        if (before == MailReplyStatus.QUARANTINED) {
            disputeAccess.requireManage();
        } else if (d.getStatus().isOpen()) {
            takeIfNeeded(d);
        } else {
            disputeAccess.requireWork(d);
        }
        Actor actor = currentActor.get();
        reply.dismiss(actor.id(), name(actor), note, Instant.now(clock));
        replies.save(reply);
        auditTrail.record(AuditEntry.of("dispute.mail_dismissed", "dispute", d.getId())
                .creditor(d.getCreditorId())
                .meta("mailReplyId", reply.getId())
                .meta("quarantineReason", reply.getQuarantineReason())
                .meta("note", reply.getDecisionNote())
                .change("status", before, MailReplyStatus.DISMISSED));
        businessEvents.log(DisputeEvents.base(BusinessEventTypes.DISPUTE_CREDITOR_MAIL_DISMISSED, d)
                .attr("from", before)
                .attr("quarantineReason", reply.getQuarantineReason()));
        metrics.disputeMailDecision("dismissed");
        return views(List.of(reply)).getFirst();
    }

    @Override
    public MailReplyView release(UUID disputeId, UUID replyId, String note) {
        disputeAccess.requireManage();
        Dispute d = disputeAccess.forCommand(disputeId);
        DisputeMailReply reply = load(d, replyId);
        Actor actor = currentActor.get();
        reply.release(actor.id(), name(actor), note, Instant.now(clock));
        replies.save(reply);
        intake.attachmentsAfterRelease(d, reply);
        auditTrail.record(AuditEntry.of("dispute.mail_released", "dispute", d.getId())
                .creditor(d.getCreditorId())
                .meta("mailReplyId", reply.getId())
                .meta("quarantineReason", reply.getQuarantineReason())
                .meta("note", reply.getReleaseNote())
                .change("status", MailReplyStatus.QUARANTINED, MailReplyStatus.PENDING_CONFIRMATION));
        businessEvents.log(DisputeEvents.base(BusinessEventTypes.DISPUTE_CREDITOR_MAIL_RELEASED, d)
                .attr("quarantineReason", reply.getQuarantineReason())
                .attr("minutesInQuarantine", Duration.between(reply.getReceivedAt(), reply.getReleasedAt()).toMinutes()));
        metrics.disputeMailDecision("released");
        return views(List.of(reply)).getFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<MailReplyView> quarantined(PageQuery page) {
        disputeAccess.requireManage();
        PageRequest pageable = PageRequest.of(page.page(), page.size(), Sort.by("receivedAt").ascending());
        var result = accessScope.hasAllCreditors()
                ? replies.findByStatus(MailReplyStatus.QUARANTINED, pageable)
                : replies.findByStatusAndCreditorIdIn(MailReplyStatus.QUARANTINED, creditorFilter(), pageable);
        List<MailReplyView> mapped = views(result.getContent());
        Map<UUID, MailReplyView> byId = mapped.stream().collect(Collectors.toMap(MailReplyView::id, Function.identity()));
        return PageResponse.of(result, r -> byId.get(r.getId()));
    }

    @Override
    @Transactional(readOnly = true)
    public EvidenceContent original(UUID disputeId, UUID replyId) {
        Dispute d = disputeAccess.visible(disputeId);
        Actor actor = currentActor.get();
        if (actor.isUser() && !actor.has(br.com.gestorcobranca.shared.security.Permission.DISPUTE_MANAGE)) {
            accessScope.requirePermission(br.com.gestorcobranca.shared.security.Permission.DISPUTE_WORK);
        }
        DisputeMailReply reply = load(d, replyId);
        accessLogger.log(AccessEvent.of(AccessEventType.FILE_DOWNLOADED, actor,
                Map.of("resource", "dispute_mail_reply", "resourceId", reply.getId().toString(),
                        "category", FileCategory.INBOUND_MAIL.name(), "sha256", reply.getRawSha256())));
        return new EvidenceContent("email-credor-" + reply.getReceivedAt().toString().substring(0, 10) + ".eml",
                "message/rfc822", reply.getRawSize(), fileStorage.open(reply.getRawKey()));
    }

    // ------------------------------------------------------------------ apoio

    private List<MailReplyView> views(List<DisputeMailReply> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Set<UUID> requestIds = list.stream().map(DisputeMailReply::getRequestId).collect(Collectors.toSet());
        Map<UUID, Integer> sequence = requestIds.stream().map(requests::findById).flatMap(java.util.Optional::stream)
                .collect(Collectors.toMap(DisputeCreditorRequest::getId, DisputeCreditorRequest::getSequenceNumber));
        return list.stream().map(r -> {
            List<DisputeDocumentQuarantine> docs = quarantine.findByMailReplyId(r.getId());
            int pending = (int) docs.stream().filter(DisputeDocumentQuarantine::isPending).count();
            int malware = (int) docs.stream().filter(q -> q.getStatus() == DisputeDocumentQuarantine.Status.INFECTED
                    || q.getStatus() == DisputeDocumentQuarantine.Status.FAILED).count();
            var v = r.getVerdicts();
            return new MailReplyView(r.getId(), r.getDisputeId(), r.getRequestId(),
                    sequence.getOrDefault(r.getRequestId(), 0), r.getCreditorId(), r.getSender(), r.getSubject(),
                    r.getText(), r.isTextTruncated(), r.getReceivedAt(), r.getStatus().name(),
                    r.getQuarantineReason() == null ? null : r.getQuarantineReason().name(),
                    r.getStatus() == MailReplyStatus.QUARANTINED && r.getQuarantineReason().releasable(),
                    MailReplyScreening.authenticated(v), v.spf().name(), v.dkim().name(), v.dmarc().name(),
                    v.virus().name(), v.spam().name(), r.getRawSha256(), r.getRawSize(), r.getAttachments(),
                    evidenceIds(r.getId()), pending, r.getAttachmentsRejected() + malware, r.getResponseId(),
                    r.getDecidedBy(), r.getDecidedByName(), r.getDecidedAt(), r.getDecisionNote(),
                    r.getReleasedByName(), r.getReleasedAt(), r.getReleaseNote());
        }).toList();
    }

    private List<UUID> evidenceIds(UUID replyId) {
        return quarantine.findByMailReplyId(replyId).stream().map(DisputeDocumentQuarantine::getEvidenceId)
                .filter(java.util.Objects::nonNull).toList();
    }

    private DisputeMailReply load(Dispute d, UUID replyId) {
        return replies.findById(replyId).filter(r -> r.getDisputeId().equals(d.getId()))
                .orElseThrow(() -> ResourceNotFoundException.of("dispute_mail_reply", replyId));
    }

    private Dispute loadForWork(UUID disputeId) {
        Dispute d = disputeAccess.forCommand(disputeId);
        takeIfNeeded(d);
        return d;
    }

    private void takeIfNeeded(Dispute d) {
        if (disputeAccess.requireWork(d) && d.getStatus().isOpen()) {
            assignments.change(d, currentActor.actorId(), DisputeAssignmentMode.TAKE, "Assumida ao trabalhar a contestação");
        }
    }

    private String name(Actor actor) {
        return actor.isUser() ? users.find(actor.id()).map(UserSummary::name).orElse(actor.name()) : actor.name();
    }

    private Collection<UUID> creditorFilter() {
        Set<UUID> allowed = accessScope.restrictCreditors(Set.of());
        return allowed.isEmpty() ? Set.of(new UUID(0L, 0L)) : allowed;
    }
}
