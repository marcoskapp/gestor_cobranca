package br.com.gestorcobranca.assistant.application.service;

import br.com.gestorcobranca.assistant.application.AssistantProperties;
import br.com.gestorcobranca.shared.jobs.JobContext;
import br.com.gestorcobranca.shared.jobs.JobHandler;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Turno do assistente numa conversa (job {@code assistant.turn}, um por mensagem recebida). Cada turno trata todas
 * as mensagens ainda não processadas: mensagens em sequência viram um único turno e os jobs seguintes terminam sem
 * efeito. Sem transação do worker (decisão 852): o orquestrador abre só uma transação curta de início, sob lock
 * consultivo, e conversa com o modelo fora dela; job que encontra outro turno da conversa em andamento não inicia
 * turno. Falhas do modelo não são repetidas — o orquestrador transfere para humanos —; só falhas de infraestrutura
 * (banco) tentam de novo.
 */
@Component
class AssistantTurnJob implements JobHandler<AssistantTurnJob.Payload> {

    static final String TYPE = "assistant.turn";

    /** @param messageId mensagem que motivou o turno (opcional; o turno trata todas as não processadas) */
    record Payload(UUID conversationId, UUID messageId) {
    }

    private final TurnOrchestrator orchestrator;
    private final AssistantProperties properties;

    AssistantTurnJob(TurnOrchestrator orchestrator, AssistantProperties properties) {
        this.orchestrator = orchestrator;
        this.properties = properties;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Class<Payload> payloadType() {
        return Payload.class;
    }

    @Override
    public int maxAttempts() {
        return properties.turnMaxAttempts();
    }

    @Override
    public boolean transactional() {
        return false;
    }

    @Override
    public void handle(JobContext context, Payload payload) {
        orchestrator.run(context.tenantId(), payload.conversationId());
    }
}
