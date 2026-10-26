package br.com.gestorcobranca.assistant.application.service;

import br.com.gestorcobranca.assistant.application.port.out.PromptSource.PromptTemplate;
import br.com.gestorcobranca.assistant.domain.model.AssistantMode;
import br.com.gestorcobranca.assistant.domain.model.AssistantSession;
import br.com.gestorcobranca.assistant.domain.model.HandoffReason;
import br.com.gestorcobranca.shared.ai.ModelTier;
import br.com.gestorcobranca.assistant.domain.model.PixRequest;
import br.com.gestorcobranca.assistant.domain.model.SessionMemory;
import br.com.gestorcobranca.assistant.domain.model.ToolCallOutcome;
import br.com.gestorcobranca.assistant.domain.model.TriggerSource;
import br.com.gestorcobranca.assistant.domain.model.TurnOutcome;
import br.com.gestorcobranca.communication.application.port.in.ConversationUseCases.ConversationView;
import br.com.gestorcobranca.shared.security.Actor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Estado de um turno em execução (uma thread; não é compartilhado). Acumula o que vai para a trilha:
 * chamadas de ferramenta, guardrails, consumo do modelo e o resultado.
 */
final class TurnContext {

    /** Chamada de ferramenta executada (gravada com o turno). */
    record ToolCallLog(int sequence, String tool, String arguments, String result, ToolCallOutcome outcome,
                       String errorCode, String referenceType, UUID referenceId, long durationMs) {
    }

    final UUID tenantId;
    final ConversationView conversation;
    final PolicyLookup.Effective policy;
    final AssistantMode mode;
    final Actor actor;
    final UUID token;
    final Instant startedAt;
    AssistantSession session;
    SessionMemory memory;
    PromptTemplate prompt;
    UUID triggerMessageId;
    List<UUID> inputMessageIds = List.of();
    String inputText = "";
    String debtorName;
    Long caseDaysOverdue;
    String caseStage;
    final List<ToolCallLog> toolCalls = new ArrayList<>();
    final Set<String> guardrails = new LinkedHashSet<>();
    String lastContext;
    String lastContextHash;
    String outputText;
    TurnOutcome outcome;
    HandoffReason handoffReason;
    TriggerSource triggerSource;
    String intent;
    BigDecimal confidence;
    String provider;
    String model;
    int modelCalls;
    /** maior nível de modelo usado no turno (nulo sem chamada ao modelo) e chamadas no nível avançado */
    ModelTier modelTier;
    int advancedModelCalls;
    /** tokens do prompt (com e sem cache) e de saída; os do cache também à parte */
    long inputTokens;
    long outputTokens;
    long cacheReadTokens;
    long cacheWriteTokens;
    BigDecimal cost = BigDecimal.ZERO;
    String costCurrency;
    long modelLatencyMs;
    UUID outboundMessageId;
    UUID draftId;
    UUID proposalId;
    UUID agreementId;
    String errorCode;
    String blockReasons;
    /** gatilho detectado nos modos de rascunho (alerta ao operador em vez de transferência) */
    String alert;
    /** pedidos de Pix avaliados no turno (gravados com o turno — {@link TurnStore}) */
    final List<PixRequest> pixRequests = new ArrayList<>();
    /** sugestão de Pix ao operador nos modos de rascunho */
    String pixSuggestion;
    private int sendSequence;

    TurnContext(UUID tenantId, ConversationView conversation, PolicyLookup.Effective policy, AssistantMode mode,
                Actor actor, UUID token, Instant startedAt) {
        this.tenantId = tenantId;
        this.conversation = conversation;
        this.policy = policy;
        this.mode = mode;
        this.actor = actor;
        this.token = token;
        this.startedAt = startedAt;
    }

    /** Chave de idempotência do n-ésimo envio do turno (reprocessamento não duplica mensagem). */
    String nextSendKey() {
        return "assistant:" + token + ":" + (++sendSequence);
    }

    int nextToolSequence() {
        return toolCalls.size() + 1;
    }

    boolean hasPolicy() {
        return policy != null;
    }

    void countTier(ModelTier tier) {
        modelTier = tier.max(modelTier);
        if (tier == ModelTier.ADVANCED) {
            advancedModelCalls++;
        }
    }

    void end(TurnOutcome outcome, HandoffReason reason, TriggerSource source) {
        this.outcome = outcome;
        this.handoffReason = reason;
        this.triggerSource = source;
    }
}
