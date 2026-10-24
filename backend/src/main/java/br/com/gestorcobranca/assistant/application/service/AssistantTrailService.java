package br.com.gestorcobranca.assistant.application.service;

import br.com.gestorcobranca.assistant.application.port.in.AssistantTrailUseCases;
import br.com.gestorcobranca.assistant.domain.model.AssistantSession;
import br.com.gestorcobranca.assistant.domain.model.AssistantTurn;
import br.com.gestorcobranca.assistant.domain.model.PixRequest;
import br.com.gestorcobranca.assistant.domain.repository.AssistantSessionRepository;
import br.com.gestorcobranca.assistant.domain.repository.AssistantTurnRepository;
import br.com.gestorcobranca.assistant.domain.repository.PixRequestRepository;
import br.com.gestorcobranca.assistant.domain.repository.ToolCallRepository;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.security.Permission;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
class AssistantTrailService implements AssistantTrailUseCases {

    private final AssistantSessionRepository sessions;
    private final AssistantTurnRepository turns;
    private final ToolCallRepository toolCalls;
    private final PixRequestRepository pixRequests;
    private final AssistantCodec codec;
    private final AccessScope accessScope;
    private final AccessLogger accessLogger;
    private final CurrentActor currentActor;

    AssistantTrailService(AssistantSessionRepository sessions, AssistantTurnRepository turns, ToolCallRepository toolCalls,
                          PixRequestRepository pixRequests, AssistantCodec codec, AccessScope accessScope,
                          AccessLogger accessLogger, CurrentActor currentActor) {
        this.pixRequests = pixRequests;
        this.sessions = sessions;
        this.turns = turns;
        this.toolCalls = toolCalls;
        this.codec = codec;
        this.accessScope = accessScope;
        this.accessLogger = accessLogger;
        this.currentActor = currentActor;
    }

    @Override
    public Optional<SessionView> session(UUID conversationId) {
        accessScope.requirePermission(Permission.ASSISTANT_REVIEW);
        return sessions.findByConversationId(conversationId).map(s -> {
            accessScope.requireVisible("conversation", conversationId, s.getCreditorId());
            return view(s);
        });
    }

    @Override
    public PageResponse<TurnView> turns(UUID conversationId, PageQuery page) {
        accessScope.requirePermission(Permission.ASSISTANT_REVIEW);
        AssistantSession s = sessions.findByConversationId(conversationId)
                .orElseThrow(() -> ResourceNotFoundException.of("conversation", conversationId));
        accessScope.requireVisible("conversation", conversationId, s.getCreditorId());
        return PageResponse.of(turns.findByConversationIdOrderByStartedAtDesc(conversationId, page.toPageable()),
                this::view);
    }

    @Override
    public TurnDetail turn(UUID turnId) {
        accessScope.requirePermission(Permission.ASSISTANT_REVIEW);
        AssistantTurn t = turns.findById(turnId).orElseThrow(() -> ResourceNotFoundException.of("assistant_turn", turnId));
        accessScope.requireVisible("assistant_turn", turnId, t.getCreditorId());
        if (t.getContentPurgedAt() == null) {
            accessLogger.log(AccessEvent.of(AccessEventType.SENSITIVE_DATA_VIEWED, currentActor.get(),
                    Map.of("resource", "assistant_turn", "resourceId", turnId.toString(), "field", "content")));
        }
        return new TurnDetail(view(t), t.getInputText(), t.getContext(), t.getOutputText(),
                toolCalls.findByTurnIdOrderBySequenceAsc(turnId).stream()
                        .map(c -> new ToolCallView(c.getSequence(), c.getToolName(), c.getArguments(), c.getResult(),
                                c.getOutcome().name(), c.getErrorCode(), c.getReferenceType(), c.getReferenceId(),
                                c.getDurationMs()))
                        .toList(),
                pixRequests.findByTurnIdOrderByRequestedAtAsc(turnId).stream().map(this::view).toList());
    }

    private PixRequestView view(PixRequest r) {
        return new PixRequestView(r.getId(), r.getDecision().name(), r.getDenialCode(),
                codec.criteria(r.getCriteria()).stream().map(c -> new CriterionView(c.criterion(), c.passed(), c.detail()))
                        .toList(), r.getAgreementId(), r.getInstallmentNumber(), r.getAmount(), r.getInstructionId(),
                r.isReusedInstruction(), r.getExpiresAt(), r.getDeliveryStatus() == null ? null : r.getDeliveryStatus().name(),
                r.getDeliveryCode(), r.getDeliveredAt(), r.getPaidAt(), r.getRequestedAt());
    }

    private SessionView view(AssistantSession s) {
        return new SessionView(s.getId(), s.getConversationId(), s.getCreditorId(), s.getPortfolioId(), s.getCaseId(),
                s.getDebtorId(), s.getStatus().name(), s.getEpisode(), s.getTurns(), s.getTotalTurns(),
                s.getIdentityStatus().name(), s.getMisunderstandings(),
                s.getHandoffReason() == null ? null : s.getHandoffReason().name(), s.getHandedOffAt(), s.getInputTokens(),
                s.getOutputTokens(), s.getCostAmount(), s.getCostCurrency(), s.getLastPolicyId(),
                s.getPendingTurnId() != null);
    }

    private TurnView view(AssistantTurn t) {
        return new TurnView(t.getId(), t.getConversationId(), t.getPolicyId(), t.getPolicyVersion(), t.getPromptCode(),
                t.getPromptVersion(), t.getMode().name(), t.getEpisode(), t.getTurnNumber(), t.getOutcome().name(),
                t.getHandoffReason() == null ? null : t.getHandoffReason().name(),
                t.getTriggerSource() == null ? null : t.getTriggerSource().name(), codec.strings(t.getGuardrails()),
                t.getIntent(), t.getConfidence(), t.getProvider(), t.getModel(), t.getModelCalls(), t.getToolCalls(),
                t.getInputTokens(), t.getOutputTokens(), t.getCostAmount(), t.getCostCurrency(), t.getModelLatencyMs(),
                t.getTotalLatencyMs(), t.getOutboundMessageId(), t.getDraftId(), t.getProposalId(), t.getAgreementId(),
                t.getErrorCode(), t.getContextSha256(), t.getContextVersion(), t.getStartedAt(), t.getFinishedAt(),
                t.getContentPurgedAt() != null);
    }
}
