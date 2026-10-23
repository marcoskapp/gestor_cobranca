package br.com.gestorcobranca.communication.application.service;

import br.com.gestorcobranca.collection.application.port.in.CaseUseCases;
import br.com.gestorcobranca.communication.application.port.in.ConversationUseCases;
import br.com.gestorcobranca.debt.application.port.in.DebtorContactUseCases;
import br.com.gestorcobranca.communication.application.port.in.MessageUseCases.MessageView;
import br.com.gestorcobranca.communication.domain.model.ChannelType;
import br.com.gestorcobranca.communication.domain.model.Conversation;
import br.com.gestorcobranca.communication.domain.model.ConversationDraft;
import br.com.gestorcobranca.communication.domain.model.ConversationHandoff;
import br.com.gestorcobranca.communication.domain.model.ConversationStatus;
import br.com.gestorcobranca.communication.domain.model.DraftMode;
import br.com.gestorcobranca.communication.domain.model.DraftStatus;
import br.com.gestorcobranca.communication.domain.model.HandoffKind;
import br.com.gestorcobranca.communication.domain.model.Message;
import br.com.gestorcobranca.communication.domain.model.MessageStatus;
import br.com.gestorcobranca.communication.domain.model.OwnerType;
import br.com.gestorcobranca.communication.domain.model.SenderType;
import br.com.gestorcobranca.communication.domain.repository.ConversationDraftRepository;
import br.com.gestorcobranca.communication.domain.repository.ConversationHandoffRepository;
import br.com.gestorcobranca.communication.domain.repository.ConversationRepository;
import br.com.gestorcobranca.communication.domain.repository.ConversationSearchCriteria;
import br.com.gestorcobranca.communication.domain.service.ResponseTargets;
import br.com.gestorcobranca.communication.application.port.out.InboxCounter;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.Permission;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
class ConversationService implements ConversationUseCases {

    private final ConversationRepository conversations;
    private final ConversationHandoffRepository handoffs;
    private final ConversationDraftRepository drafts;
    private final ConversationAccess access;
    private final ConversationOwnership ownership;
    private final MessageService messageService;
    private final br.com.gestorcobranca.shared.security.AccessScope accessScope;
    private final br.com.gestorcobranca.debt.application.port.in.DebtorUseCases debtors;
    private final DebtorContactUseCases debtorContacts;
    private final CaseReader cases;
    private final AuditTrail auditTrail;
    private final BusinessEventLogger businessEvents;
    private final CommunicationMetrics metrics;
    private final ResponseTargetProvider responseTargets;
    private final InboxCounter counter;
    private final Clock clock;

    ConversationService(ConversationRepository conversations, ConversationHandoffRepository handoffs,
                        ConversationDraftRepository drafts, ConversationAccess access, ConversationOwnership ownership,
                        MessageService messageService, br.com.gestorcobranca.shared.security.AccessScope accessScope,
                        br.com.gestorcobranca.debt.application.port.in.DebtorUseCases debtors,
                        DebtorContactUseCases debtorContacts, CaseReader cases, AuditTrail auditTrail,
                        BusinessEventLogger businessEvents, CommunicationMetrics metrics,
                        ResponseTargetProvider responseTargets, InboxCounter counter, Clock clock) {
        this.conversations = conversations;
        this.handoffs = handoffs;
        this.drafts = drafts;
        this.access = access;
        this.ownership = ownership;
        this.messageService = messageService;
        this.accessScope = accessScope;
        this.debtors = debtors;
        this.debtorContacts = debtorContacts;
        this.cases = cases;
        this.auditTrail = auditTrail;
        this.businessEvents = businessEvents;
        this.metrics = metrics;
        this.responseTargets = responseTargets;
        this.counter = counter;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ConversationView> inbox(InboxFilter filter, PageQuery page) {
        InboxFilter f = filter == null ? new InboxFilter(null, null, null, null, null, null, null, null, null, null, null)
                : filter;
        Set<UUID> allowed = access.creditorFilter();
        if (f.creditorId() != null && allowed != null && !allowed.contains(f.creditorId())) {
            return PageResponse.of(org.springframework.data.domain.Page.empty());
        }
        ConversationSearchCriteria criteria = new ConversationSearchCriteria(allowed, f.creditorId(), f.portfolioId(),
                f.debtorId(), f.caseId(),
                CommunicationEnums.parseOptional(ChannelType.class, f.channel(), "conversation.channel_invalid", "Canal"),
                CommunicationEnums.parseOptional(ConversationStatus.class, f.status(), "conversation.status_invalid",
                        "Situação"),
                CommunicationEnums.parseSet(OwnerType.class, f.ownerTypes(), "conversation.owner_invalid", "Dono"),
                f.ownerUserId(), f.teamId(),
                f.requiredProfile() == null || f.requiredProfile().isBlank() ? null : f.requiredProfile().trim(),
                f.awaitingReply());
        // aguardando resposta primeiro (mais antigo primeiro: SLA), depois por atividade recente
        Sort sort = Boolean.TRUE.equals(f.awaitingReply())
                ? Sort.by(Sort.Order.asc("awaitingSince"))
                : Sort.by(Sort.Order.desc("lastMessageAt").nullsLast(), Sort.Order.desc("createdAt"));
        Instant now = Instant.now(clock);
        ResponseTargets targets = responseTargets.current();
        return PageResponse.of(conversations.search(criteria, page.toPageable(sort)),
                c -> CommunicationViews.conversation(c, now, targets));
    }

    @Override
    @Transactional(readOnly = true)
    public InboxCounts counts(String channel, Boolean awaitingReply) {
        ChannelType type = CommunicationEnums.parseOptional(ChannelType.class, channel, "conversation.channel_invalid",
                "Canal");
        Instant now = Instant.now(clock);
        ResponseTargets targets = responseTargets.current();
        java.util.Map<String, Instant> breachedBefore = new java.util.LinkedHashMap<>();
        for (ChannelType ch : ChannelType.values()) {
            breachedBefore.put(ch.name(), now.minus(targets.target(ch).breach()));
        }
        Actor actor = access.actor();
        InboxCounter.Counts c = counter.count(access.creditorFilter(), actor.id(), type == null ? null : type.name(),
                Boolean.TRUE.equals(awaitingReply), breachedBefore);
        return new InboxCounts(c.mine(), c.queue(), c.assistant(), c.all(), c.breached());
    }

    @Override
    @Transactional(readOnly = true)
    public ConversationDetail get(UUID conversationId) {
        Conversation c = access.visible(conversationId);
        return new ConversationDetail(CommunicationViews.conversation(c, Instant.now(clock), responseTargets.current()),
                handoffViews(c),
                drafts.findByConversationIdAndStatusOrderByCreatedAtDesc(c.getId(), DraftStatus.PENDING).stream()
                        .map(CommunicationViews::draft).toList());
    }

    @Override
    public ConversationView markRead(UUID conversationId) {
        Conversation c = access.forCommand(conversationId);
        c.markRead();
        return view(conversations.save(c));
    }

    @Override
    public ConversationView identify(UUID conversationId, UUID debtorId, UUID creditorId) {
        Conversation c = open(conversationId);
        Guard.requireState(c.getDebtorId() == null, "conversation.already_identified", "Conversa já identificada");
        UUID creditor = c.getCreditorId() != null ? c.getCreditorId() : creditorId;
        Guard.require(creditor != null, "conversation.creditor_required", "Informe o credor da conversa");
        Guard.require(creditorId == null || creditorId.equals(creditor), "conversation.creditor_mismatch",
                "A conversa pertence a outro credor");
        accessScope.requireCreditor(creditor);
        debtors.get(debtorId);
        String type = c.getChannel() == ChannelType.EMAIL ? "EMAIL" : "PHONE";
        DebtorContactUseCases.ChannelContact contact = debtorContacts.contactsForChannel(debtorId, type).stream()
                .filter(k -> debtorContacts.tryFingerprint(k.type(), k.value())
                        .map(f -> f.hash().equals(c.getContactHash())).orElse(false))
                .findFirst()
                .orElseThrow(() -> new br.com.gestorcobranca.shared.domain.BusinessRuleException(
                        "conversation.contact_not_of_debtor", "O contato da conversa não consta do cadastro do devedor"));
        if (c.getCreditorId() == null && conversations.findOpen(c.getChannelAccountId(), c.getContactHash(), creditor).isPresent()) {
            throw new ConflictException("conversation.duplicate_open",
                    "Já existe conversa aberta deste contato para o credor; encerre esta e use a existente");
        }
        c.identify(debtorId, creditor, contact.contactId());
        java.util.Optional<CaseUseCases.CaseView> active = cases.singleActive(debtorId, creditor);
        Conversation saved = conversations.save(c);
        auditTrail.record(AuditEntry.of("conversation.identified", "conversation", saved.getId())
                .creditor(creditor)
                .change("debtorId", null, debtorId)
                .meta("contactId", contact.contactId()));
        if (active.isPresent()) {
            CaseUseCases.CaseView kase = active.get();
            saved.linkCase(debtorId, kase.portfolioId(), kase.id());
            saved = ownership.syncFromCase(conversations.save(saved), kase, true);
        }
        return view(saved);
    }

    @Override
    public ConversationView takeOver(UUID conversationId, String reason) {
        Conversation c = open(conversationId);
        if (c.getOwnerType() == OwnerType.USER && Objects.equals(c.getOwnerUserId(), access.actor().id())) {
            return view(c);
        }
        access.requireWorkable(c);
        return view(ownership.takeOver(c, reason));
    }

    @Override
    public ConversationView release(UUID conversationId, String reason) {
        Conversation c = open(conversationId);
        Guard.requireState(c.getOwnerType() == OwnerType.USER, "conversation.not_assigned",
                "A conversa não está com um operador");
        return view(ownership.release(c, reason));
    }

    @Override
    public ConversationView assign(UUID conversationId, UUID userId, String reason) {
        Guard.require(userId != null, "conversation.assignee_required", "Operador obrigatório");
        Conversation c = open(conversationId);
        return view(ownership.assign(c, userId, reason));
    }

    @Override
    public ConversationView returnToAssistant(UUID conversationId, String reason) {
        Conversation c = open(conversationId);
        if (c.getOwnerType() == OwnerType.ASSISTANT) {
            return view(c);
        }
        return view(ownership.returnToAssistant(c, reason));
    }

    @Override
    public ConversationView handoffToHumans(UUID conversationId, HandoffCommand command) {
        Guard.require(command != null, "handoff.required", "Dados da transferência obrigatórios");
        Conversation c = open(conversationId);
        Actor actor = access.actor();
        if (actor.isAssistant()) {
            Guard.requireState(c.getOwnerType() == OwnerType.ASSISTANT, "conversation.not_with_assistant",
                    "O assistente só transfere conversas que conduz");
        } else if (actor.isUser()) {
            boolean owner = c.getOwnerType() == OwnerType.USER && actor.id().equals(c.getOwnerUserId());
            if (!owner && !actor.has(Permission.INBOX_MANAGE)) {
                throw new ConflictException("conversation.not_assigned_to_user",
                        "Somente o dono ou a supervisão transferem a conversa");
            }
        }
        return view(ownership.escalate(c, new ConversationOwnership.Change(HandoffKind.ESCALATION, reason(command.reasonCode()),
                command.summary(), command.summary(), profile(command.requiredProfile()), command.referenceType(),
                command.referenceId())));
    }

    @Override
    public ConversationView close(UUID conversationId, String reason) {
        Conversation c = open(conversationId);
        Actor actor = access.actor();
        if (actor.isUser() && !actor.has(Permission.INBOX_MANAGE)) {
            boolean owner = c.getOwnerType() == OwnerType.USER && actor.id().equals(c.getOwnerUserId());
            if (!owner) {
                throw new ConflictException("conversation.not_assigned_to_user",
                        "Somente o dono ou a supervisão encerram a conversa");
            }
        }
        Instant now = Instant.now(clock);
        Conversation.Owner from = c.owner();
        Instant openedAt = c.getCreatedAt();
        c.close(actor.id(), reason, now);
        Conversation saved = conversations.save(c);
        handoffs.save(new ConversationHandoff(saved, from, saved.owner(), HandoffKind.CLOSE, "CLOSE", null,
                actor.type().name(), actor.id(), null, null, now));
        for (ConversationDraft draft : drafts.findByConversationIdAndStatusOrderByCreatedAtDesc(c.getId(), DraftStatus.PENDING)) {
            draft.expire(now);
            drafts.save(draft);
        }
        auditTrail.record(AuditEntry.of("conversation.closed", "conversation", saved.getId())
                .creditor(saved.getCreditorId())
                .reason(reason)
                .change("status", ConversationStatus.OPEN, ConversationStatus.CLOSED)
                .change("ownerType", from.type(), saved.getOwnerType()));
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.CONVERSATION_CLOSED)
                .creditor(saved.getCreditorId()).portfolio(saved.getPortfolioId()).debtor(saved.getDebtorId())
                .collectionCase(saved.getCaseId()).reference("conversation", saved.getId())
                .channel(saved.getChannel().name())
                .attr("from", from.type())
                .attr("messages", saved.getMessageCount())
                .attr("identified", saved.getDebtorId() != null)
                .durationMs(openedAt == null ? 0 : Math.max(0, Duration.between(openedAt, now).toMillis())));
        metrics.handoff(HandoffKind.CLOSE.name(), from.type().name(), OwnerType.NONE.name());
        return view(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<HandoffView> handoffs(UUID conversationId) {
        return handoffViews(access.visible(conversationId));
    }

    @Override
    public DraftView proposeDraft(UUID conversationId, ProposeDraftCommand command) {
        Guard.require(command != null, "draft.required", "Dados do rascunho obrigatórios");
        Actor actor = access.actor();
        if (!actor.isAssistant() && !actor.isSystem()) {
            throw new ConflictException("draft.assistant_only", "Rascunhos são propostos pelo assistente");
        }
        Conversation c = open(conversationId);
        Guard.requireState(c.getOwnerType() != OwnerType.ASSISTANT, "draft.conversation_with_assistant",
                "No modo autônomo o assistente responde diretamente; rascunhos são para conversas com humanos");
        DraftMode mode = CommunicationEnums.parse(DraftMode.class, command.mode(), "draft.mode_invalid", "Modo");
        ConversationDraft draft = drafts.save(new ConversationDraft(c, mode, actor.type().name(), actor.id(),
                command.content(), command.rationale()));
        metrics.draft(mode.name(), "proposed");
        return CommunicationViews.draft(draft);
    }

    @Override
    public MessageView sendDraft(UUID conversationId, UUID draftId, String editedContent) {
        Conversation c = open(conversationId);
        ConversationDraft draft = pendingDraft(c, draftId);
        Actor actor = access.actor();
        Guard.requireState(actor.isUser(), "draft.user_required", "Somente um operador envia o rascunho");
        String content = editedContent == null || editedContent.isBlank() ? draft.getContent() : editedContent;
        Message message = messageService.queue(c, SenderType.USER, new MessageService.Content(null, null, content, null),
                null, draft.getId(), null);
        Instant now = Instant.now(clock);
        if (message.getStatus() == MessageStatus.FAILED) {
            // negado pelo porteiro: o rascunho continua pendente para nova decisão
            return CommunicationViews.message(message);
        }
        draft.sent(content, message.getId(), actor.id(), now);
        drafts.save(draft);
        metrics.draft(draft.getMode().name(), draft.isEdited() ? "edited" : "sent");
        auditTrail.record(AuditEntry.of("conversation_draft.sent", "conversation_draft", draft.getId())
                .creditor(c.getCreditorId())
                .meta("conversationId", c.getId())
                .meta("messageId", message.getId())
                .meta("edited", draft.isEdited())
                .meta("mode", draft.getMode()));
        return CommunicationViews.message(message);
    }

    @Override
    public DraftView discardDraft(UUID conversationId, UUID draftId) {
        Conversation c = open(conversationId);
        ConversationDraft draft = pendingDraft(c, draftId);
        Actor actor = access.actor();
        draft.discard(actor.id(), Instant.now(clock));
        drafts.save(draft);
        metrics.draft(draft.getMode().name(), "discarded");
        auditTrail.record(AuditEntry.of("conversation_draft.discarded", "conversation_draft", draft.getId())
                .creditor(c.getCreditorId())
                .meta("conversationId", c.getId())
                .meta("mode", draft.getMode()));
        return CommunicationViews.draft(draft);
    }

    private ConversationDraft pendingDraft(Conversation c, UUID draftId) {
        ConversationDraft draft = drafts.findById(draftId)
                .filter(d -> d.getConversationId().equals(c.getId()))
                .orElseThrow(() -> ResourceNotFoundException.of("conversation_draft", draftId));
        Guard.requireState(draft.getStatus() == DraftStatus.PENDING, "draft.not_pending", "Rascunho já decidido");
        return draft;
    }

    private Conversation open(UUID conversationId) {
        Conversation c = access.forCommand(conversationId);
        Guard.requireState(c.isOpen(), "conversation.closed", "Conversa encerrada");
        return c;
    }

    private List<HandoffView> handoffViews(Conversation c) {
        return handoffs.findByConversationIdOrderByOccurredAtAsc(c.getId()).stream().map(CommunicationViews::handoff).toList();
    }

    private ConversationView view(Conversation c) {
        return CommunicationViews.conversation(c, Instant.now(clock), responseTargets.current());
    }

    private static String reason(String code) {
        return code == null || code.isBlank() ? "UNSPECIFIED" : code.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private static String profile(String profile) {
        return profile == null || profile.isBlank() ? null : profile.trim().toUpperCase(java.util.Locale.ROOT);
    }
}
