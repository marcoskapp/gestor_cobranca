package br.com.gestorcobranca.assistant.application.port.out;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Porta de saída para o provedor de modelo de linguagem com <strong>chamada de ferramentas</strong>
 * (function calling). Contrato neutro de provedor: o adaptador traduz para a API escolhida (issue #12;
 * Amazon Bedrock exigiria aprovação de serviço AWS novo) e devolve tokens, custo e latência.
 * <p>
 * Regras do contrato (ver docs/tecnico/desenho/assistente.md §6):
 * <ul>
 *   <li>{@link ModelRequest#contextDocument()} é o contexto minimizado (JSON) e deve ser entregue ao
 *       modelo como instrução de sistema, separado das mensagens do devedor;</li>
 *   <li>o adaptador respeita {@code maxOutputTokens} e {@code timeout} (tempo de leitura do cliente
 *       HTTP) e lança {@link ModelTimeoutException}/{@link ModelUnavailableException} em falhas
 *       transitórias e {@link ModelRejectedException} em recusa definitiva (conteúdo, cota, credencial);</li>
 *   <li>nenhuma retentativa dentro do adaptador além de uma para 429/5xx imediatos: o orquestrador
 *       transfere para humano em caso de falha (o devedor não fica sem resposta);</li>
 *   <li>a requisição não leva identificadores de pessoa: {@code metadata} só com ids opacos de turno e
 *       assessoria para correlação no provedor; o provedor precisa estar contratado sem retenção nem
 *       treinamento com os dados (política de uso de dados — issue #12);</li>
 *   <li>credenciais do provedor nunca em {@code application.yml}: Parameter Store/KMS (pendente de aprovação).</li>
 * </ul>
 */
public interface LanguageModelPort {

    /** Identificador estável do provedor (tag de métricas e trilha). */
    String provider();

    ModelResponse complete(ModelRequest request);

    enum Role { USER, ASSISTANT, TOOL }

    /**
     * @param role       autor: devedor ({@code USER}), assistente ({@code ASSISTANT}) ou resultado de ferramenta ({@code TOOL})
     * @param content    texto (já minimizado) ou resultado da ferramenta em JSON
     * @param toolCalls  chamadas pedidas pelo assistente nesta mensagem
     * @param toolCallId chamada respondida por uma mensagem {@code TOOL}
     */
    record ModelMessage(Role role, String content, List<ToolCall> toolCalls, String toolCallId) {

        public ModelMessage {
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        }

        public static ModelMessage user(String content) {
            return new ModelMessage(Role.USER, content, List.of(), null);
        }

        public static ModelMessage assistant(String content, List<ToolCall> calls) {
            return new ModelMessage(Role.ASSISTANT, content, calls, null);
        }

        public static ModelMessage tool(String callId, String resultJson) {
            return new ModelMessage(Role.TOOL, resultJson, List.of(), callId);
        }
    }

    /** Ferramenta exposta: nome, descrição e parâmetros em JSON Schema. */
    record ToolDefinition(String name, String description, String parametersSchema) {
    }

    /** @param argumentsJson argumentos em JSON (validados pelo servidor antes de qualquer efeito) */
    record ToolCall(String id, String name, String argumentsJson) {
    }

    /**
     * @param systemPrompt    prompt versionado (recurso do módulo)
     * @param contextDocument contexto minimizado (JSON) — ver {@code ModelContext}
     * @param metadata        ids opacos para correlação (turno, assessoria); nunca dado pessoal
     */
    record ModelRequest(String systemPrompt, String contextDocument, List<ModelMessage> messages,
                        List<ToolDefinition> tools, int maxOutputTokens, Duration timeout, Map<String, String> metadata) {

        public ModelRequest {
            messages = List.copyOf(messages);
            tools = tools == null ? List.of() : List.copyOf(tools);
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }

    enum StopReason { END_TURN, TOOL_USE, MAX_TOKENS, CONTENT_FILTERED }

    record Usage(long inputTokens, long outputTokens) {
    }

    /** @param amount custo estimado da chamada na moeda do contrato com o provedor */
    record Cost(BigDecimal amount, String currency) {
    }

    /**
     * @param text      resposta em texto (pode vir junto com chamadas de ferramenta)
     * @param toolCalls ferramentas pedidas
     * @param model     identificador do modelo efetivamente usado
     */
    record ModelResponse(String text, List<ToolCall> toolCalls, StopReason stopReason, Usage usage, Cost cost,
                         String model, Duration latency) {

        public ModelResponse {
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        }
    }

    /** Falha do provedor; o turno termina com transferência para humano. */
    abstract class ModelException extends RuntimeException {

        protected ModelException(String message, Throwable cause) {
            super(message, cause);
        }

        public abstract String code();
    }

    class ModelTimeoutException extends ModelException {

        public ModelTimeoutException(String message, Throwable cause) {
            super(message, cause);
        }

        @Override
        public String code() {
            return "MODEL_TIMEOUT";
        }
    }

    class ModelUnavailableException extends ModelException {

        public ModelUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }

        @Override
        public String code() {
            return "MODEL_UNAVAILABLE";
        }
    }

    class ModelRejectedException extends ModelException {

        public ModelRejectedException(String message, Throwable cause) {
            super(message, cause);
        }

        @Override
        public String code() {
            return "MODEL_REJECTED";
        }
    }
}
