package br.com.gestorcobranca.communication.application.service;

import br.com.gestorcobranca.collection.application.port.in.CaseUseCases.CaseView;
import br.com.gestorcobranca.collection.application.port.in.CaseWorkUseCases;
import br.com.gestorcobranca.collection.application.port.in.CaseWorkUseCases.AssignCommand;
import br.com.gestorcobranca.communication.domain.model.Conversation;
import br.com.gestorcobranca.communication.domain.model.Conversation.Owner;
import br.com.gestorcobranca.communication.domain.model.ConversationDraft;
import br.com.gestorcobranca.communication.domain.model.ConversationHandoff;
import br.com.gestorcobranca.communication.domain.model.DraftStatus;
import br.com.gestorcobranca.communication.domain.model.HandoffKind;
import br.com.gestorcobranca.communication.domain.model.OwnerType;
import br.com.gestorcobranca.communication.domain.repository.ConversationDraftRepository;
import br.com.gestorcobranca.communication.domain.repository.ConversationHandoffRepository;
import br.com.gestorcobranca.communication.domain.repository.ConversationRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CurrentActor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Mudanças de dono do atendimento. Com caso ativo, o comando é aplicado primeiro no caso
 * ({@link CaseWorkUseCases}: valida operador, escopo e quem pode mudar, e grava o histórico de
 * atribuição); o dono resultante do caso é então aplicado à conversa e às demais conversas abertas
 * do caso. Sem caso, o dono é da própria conversa.
 * <p>
 * Cada mudança grava {@link ConversationHandoff}, auditoria, evento de negócio
 * {@code conversation.handoff} ({@code assistant.handoff} quando o assistente transfere) e a
 * métrica {@code communication.handoffs}. Rascunhos pendentes do assistente expiram quando a
 * conversa deixa de estar com um operador.
 */
@Component
class ConversationOwnership {

    private final ConversationRepository conversations;
    private final ConversationHandoffRepository handoffs;
    private final ConversationDraftRepository drafts;
    private final CaseReader cases;
    private final CaseWorkUseCases work;
    private final CurrentActor currentActor;
    private final AuditTrail auditTrail;
    private final BusinessEventLogger businessEvents;
    private final CommunicationMetrics metrics;
    private final Clock clock;

    ConversationOwnership(ConversationRepository conversations, ConversationHandoffRepository handoffs,
                          ConversationDraftRepository drafts, CaseReader cases, CaseWorkUseCases work,
                          CurrentActor currentActor, AuditTrail auditTrail, BusinessEventLogger businessEvents,
                          CommunicationMetrics metrics, Clock clock) {
        this.conversations = conversations;
        this.handoffs = handoffs;
        this.drafts = drafts;
        this.cases = cases;
        this.work = work;
        this.currentActor = currentActor;
        this.auditTrail = auditTrail;
        this.businessEvents = businessEvents;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** Pedido de mudança de dono. */
    record Change(HandoffKind kind, String reasonCode, String reasonText, String summary, String requiredProfile,
                  String referenceType, UUID referenceId) {

        static Change of(HandoffKind kind, String reasonCode, String reasonText) {
            return new Change(kind, reasonCode, reasonText, null, null, null, null);
        }
    }

    /** Operador corrente assume. */
    Conversation takeOver(Conversation c, String reason) {
        Actor actor = requireUser();
        Optional<CaseView> kase = activeCase(c);
        Owner target = kase.map(k -> fromCase(work.takeOver(k.id(), text(reason, "Conversa assumida")), c, false))
                .orElse(Owner.user(actor.id(), c.getOwnerTeamId()));
        return apply(c, target, Change.of(HandoffKind.TAKE_OVER, "TAKE_OVER", reason));
    }

    /** Devolve à fila mantendo o perfil exigido. */
    Conversation release(Conversation c, String reason) {
        Actor actor = currentActor.get();
        Optional<CaseView> kase = activeCase(c);
        Owner target;
        if (kase.isPresent()) {
            target = fromCase(work.release(kase.get().id(), text(reason, "Conversa devolvida à fila")), c, true);
        } else {
            if (c.getOwnerType() == OwnerType.USER && !Objects.equals(c.getOwnerUserId(), actor.id())
                    && !actor.has(br.com.gestorcobranca.shared.security.Permission.CASE_ASSIGN)) {
                throw new ConflictException("conversation.not_assigned_to_user", "Somente o dono devolve a conversa à fila");
            }
            target = Owner.queue(c.getOwnerTeamId(), c.getRequiredProfile());
        }
        return apply(c, target, Change.of(HandoffKind.RELEASE, "RELEASE", reason));
    }

    /** Atribui a um operador: exige caso (o collection valida operador ativo, permissão e escopo). */
    Conversation assign(Conversation c, UUID userId, String reason) {
        CaseView kase = activeCase(c).orElseThrow(() -> new BusinessRuleException("conversation.assign_requires_case",
                "Atribuição a operador exige conversa vinculada a um caso ativo; o operador pode assumi-la"));
        Owner target = fromCase(work.assign(kase.id(), new AssignCommand(userId, null, text(reason, "Conversa atribuída"))),
                c, false);
        return apply(c, target, Change.of(HandoffKind.ASSIGNMENT, "ASSIGNMENT", reason));
    }

    /** Devolve ao assistente (o dono, quem tem CASE_ASSIGN; sem caso, quem pode trabalhar a conversa). */
    Conversation returnToAssistant(Conversation c, String reason) {
        Actor actor = currentActor.get();
        Optional<CaseView> kase = activeCase(c);
        Owner target;
        if (kase.isPresent()) {
            target = fromCase(work.handToAssistant(kase.get().id(), text(reason, "Conversa devolvida ao assistente")), c,
                    false);
        } else {
            if (actor.isUser() && c.getOwnerType() == OwnerType.USER && !Objects.equals(c.getOwnerUserId(), actor.id())
                    && !actor.has(br.com.gestorcobranca.shared.security.Permission.CASE_ASSIGN)) {
                throw new ConflictException("conversation.not_assigned_to_user",
                        "Somente o dono devolve a conversa ao assistente");
            }
            target = Owner.assistant();
        }
        return apply(c, target, Change.of(HandoffKind.RETURN_TO_ASSISTANT, "RETURN_TO_ASSISTANT", reason));
    }

    /**
     * Transferência para humanos com motivo, resumo e perfil exigido. O caso volta à fila quando
     * estava com o assistente ou com um operador (quem pede precisa poder devolvê-lo).
     */
    Conversation escalate(Conversation c, Change change) {
        Optional<CaseView> kase = activeCase(c);
        UUID team = c.getOwnerTeamId();
        if (kase.isPresent()) {
            CaseView k = kase.get();
            if ("ASSISTANT".equals(k.ownerType()) || "USER".equals(k.ownerType())) {
                k = work.release(k.id(), "Transferência para humano: " + change.reasonCode());
            }
            team = k.assigneeTeamId();
        }
        return apply(c, Owner.queue(team, change.requiredProfile()), change);
    }

    /**
     * Alinha a conversa ao dono atual do caso (mudança feita diretamente no collection), sem
     * comandar o caso.
     *
     * @param needsHuman a conversa aguarda atendimento (mensagem recebida): caso sem dono → fila
     */
    Conversation syncFromCase(Conversation c, CaseView kase, boolean needsHuman) {
        Owner target = fromCase(kase, c, needsHuman);
        if (target.equals(c.owner())) {
            return c;
        }
        return applyOne(c, target, Change.of(HandoffKind.CASE_SYNC, "CASE_SYNC", "Dono alinhado ao caso"));
    }

    /** Mensagem recebida em conversa sem dono e sem caso: vai para a fila da caixa de entrada. */
    Conversation awaitHuman(Conversation c) {
        return applyOne(c, Owner.queue(c.getOwnerTeamId(), c.getRequiredProfile()),
                Change.of(HandoffKind.REOPEN, "INBOUND_MESSAGE", "Mensagem recebida aguardando atendimento"));
    }

    /** Caso vinculado e ativo; encerrado ou inexistente → a conversa segue sem caso. */
    Optional<CaseView> activeCase(Conversation c) {
        return cases.active(c.getCaseId());
    }

    /** Dono da conversa correspondente ao do caso. */
    static Owner fromCase(CaseView kase, Conversation c, boolean needsHuman) {
        return fromCase(kase, c.getRequiredProfile(), needsHuman || c.getOwnerType() == OwnerType.QUEUE);
    }

    /**
     * @param waitingHuman a conversa aguarda um humano: caso sem dono ({@code NONE}) → fila
     */
    static Owner fromCase(CaseView kase, String requiredProfile, boolean waitingHuman) {
        return switch (kase.ownerType()) {
            case "USER" -> new Owner(OwnerType.USER, kase.assigneeUserId(), kase.assigneeTeamId(), requiredProfile);
            case "ASSISTANT" -> Owner.assistant();
            case "QUEUE" -> Owner.queue(kase.assigneeTeamId(), requiredProfile);
            default -> waitingHuman ? Owner.queue(kase.assigneeTeamId(), requiredProfile) : Owner.NONE;
        };
    }

    /** Aplica à conversa e às demais conversas abertas do mesmo caso. */
    private Conversation apply(Conversation c, Owner target, Change change) {
        Conversation saved = applyOne(c, target, change);
        if (c.getCaseId() != null) {
            List<Conversation> siblings = new ArrayList<>(conversations.findOpenByCaseId(c.getCaseId()));
            siblings.removeIf(s -> s.getId().equals(c.getId()));
            for (Conversation sibling : siblings) {
                if (!target.equals(sibling.owner())) {
                    applyOne(sibling, target, change);
                }
            }
        }
        return saved;
    }

    private Conversation applyOne(Conversation c, Owner target, Change change) {
        Instant now = Instant.now(clock);
        Instant previousSince = c.getOwnerSince();
        Owner from = c.changeOwner(target, now);
        Conversation saved = conversations.save(c);
        Actor actor = currentActor.get();
        handoffs.save(new ConversationHandoff(saved, from, saved.owner(), change.kind(), change.reasonCode(),
                change.summary(), actor.type().name(), actor.id(), change.referenceType(), change.referenceId(), now));
        if (saved.getOwnerType() != OwnerType.USER) {
            expireDrafts(saved, now);
        }
        auditTrail.record(AuditEntry.of("conversation.owner_changed", "conversation", saved.getId())
                .creditor(saved.getCreditorId())
                .reason(change.reasonText() == null || change.reasonText().isBlank() ? change.reasonCode()
                        : change.reasonText())
                .change("ownerType", from.type(), saved.getOwnerType())
                .change("ownerUserId", from.userId(), saved.getOwnerUserId())
                .change("requiredProfile", from.requiredProfile(), saved.getRequiredProfile())
                .meta("kind", change.kind())
                .meta("caseId", saved.getCaseId()));
        logBusiness(saved, from, previousSince, change);
        metrics.handoff(change.kind().name(), from.type().name(), saved.getOwnerType().name());
        return saved;
    }

    private void logBusiness(Conversation c, Owner from, Instant previousSince, Change change) {
        boolean assistantEscalation = change.kind() == HandoffKind.ESCALATION && from.type() == OwnerType.ASSISTANT;
        String type = assistantEscalation ? BusinessEventTypes.ASSISTANT_HANDOFF : BusinessEventTypes.CONVERSATION_HANDOFF;
        // tempo com o dono anterior
        long ownedMs = previousSince == null ? 0
                : Math.max(0, java.time.Duration.between(previousSince, Instant.now(clock)).toMillis());
        businessEvents.log(BusinessEvent.of(type)
                .creditor(c.getCreditorId())
                .portfolio(c.getPortfolioId())
                .debtor(c.getDebtorId())
                .collectionCase(c.getCaseId())
                .reference("conversation", c.getId())
                .channel(c.getChannel().name())
                .attr("kind", change.kind())
                .attr("reason", change.reasonCode())
                .attr("from", from.type())
                .attr("to", c.getOwnerType())
                .attr("fromUserId", from.userId() == null ? null : from.userId().toString())
                .attr("toUserId", c.getOwnerUserId() == null ? null : c.getOwnerUserId().toString())
                .attr("requiredProfile", c.getRequiredProfile())
                .attr("referenceType", change.referenceType())
                .attr("awaitingReply", c.getAwaitingSince() != null)
                .durationMs(ownedMs));
    }

    private void expireDrafts(Conversation c, Instant now) {
        for (ConversationDraft draft : drafts.findByConversationIdAndStatusOrderByCreatedAtDesc(c.getId(), DraftStatus.PENDING)) {
            draft.expire(now);
            drafts.save(draft);
        }
    }

    private Actor requireUser() {
        Actor actor = currentActor.get();
        if (!actor.isUser()) {
            throw new ConflictException("conversation.user_required", "Somente um operador assume a conversa");
        }
        return actor;
    }

    private static String text(String reason, String fallback) {
        return reason == null || reason.isBlank() ? fallback : reason.trim();
    }
}
