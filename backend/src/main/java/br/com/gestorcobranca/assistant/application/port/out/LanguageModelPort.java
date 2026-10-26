package br.com.gestorcobranca.assistant.application.port.out;

import br.com.gestorcobranca.assistant.domain.model.ModelTier;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Porta de saída para o provedor de modelo de linguagem com <strong>chamada de ferramentas</strong>
 * (function calling). Contrato neutro de provedor: o adaptador traduz para a API escolhida e devolve tokens,
 * custo e latência. Adaptadores: {@code BedrockLanguageModel} (Amazon Bedrock, Converse API, modelos Claude —
 * aprovado na issue #12) e {@code SandboxLanguageModel} (simulado, local/teste), escolhidos por
 * {@code app.assistant.provider} ({@code none}, {@code sandbox} ou {@code bedrock}; um só por instância).
 * <p>
 * Regras do contrato (ver docs/tecnico/desenho/assistente.md §6):
 * <ul>
 *   <li>{@link ModelRequest#systemPrompt()} e {@link ModelRequest#contextDocument()} (contexto minimizado em
 *       JSON) são entregues ao modelo como instruções de sistema, em blocos separados e nunca concatenados ao
 *       texto do devedor (que só aparece nas mensagens {@code USER});</li>
 *   <li>{@link ModelRequest#tier()} é o nível de modelo escolhido pelo servidor ({@link ModelTier}); o adaptador
 *       o mapeia para o modelo ou perfil de inferência configurado e devolve em {@link ModelResponse#model()} o
 *       identificador efetivamente usado;</li>
 *   <li>o adaptador respeita {@code maxOutputTokens} (sempre enviado ao provedor) e {@code timeout} (tempo
 *       máximo da chamada, inclusive a retentativa) e lança {@link ModelTimeoutException} (tempo esgotado),
 *       {@link ModelUnavailableException} (limite de taxa, 5xx, rede, saída malformada) ou
 *       {@link ModelRejectedException} (recusa definitiva: validação, acesso negado, modelo não habilitado,
 *       cota, janela de contexto); resposta bloqueada pelo filtro de conteúdo do provedor volta como
 *       {@link StopReason#CONTENT_FILTERED} (o orquestrador trata como guardrail);</li>
 *   <li>nenhuma retentativa dentro do adaptador além de <strong>uma</strong> para limite de taxa/5xx imediatos:
 *       o orquestrador transfere para humano em caso de falha (o devedor não fica sem resposta);</li>
 *   <li>{@link Usage} separa os tokens de entrada não cacheados dos lidos e gravados no cache de prompt do
 *       provedor; o custo é calculado pelo adaptador com a tabela de preços da configuração (nunca no código);</li>
 *   <li>a requisição não leva identificadores de pessoa: {@code metadata} só com ids opacos de turno e
 *       assessoria para correlação no provedor; o provedor precisa estar contratado sem retenção nem
 *       treinamento com os dados (no Bedrock, o provedor do modelo não acessa prompts nem respostas);</li>
 *   <li>credenciais do provedor nunca em {@code application.yml}: no Bedrock, cadeia padrão do SDK (papel IAM
 *       da instância EC2).</li>
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
     * @param tier            nível de modelo escolhido pelo servidor (nulo = {@link ModelTier#STANDARD})
     */
    record ModelRequest(String systemPrompt, String contextDocument, List<ModelMessage> messages,
                        List<ToolDefinition> tools, int maxOutputTokens, Duration timeout, Map<String, String> metadata,
                        ModelTier tier) {

        public ModelRequest {
            messages = List.copyOf(messages);
            tools = tools == null ? List.of() : List.copyOf(tools);
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
            tier = tier == null ? ModelTier.STANDARD : tier;
        }

        public ModelRequest(String systemPrompt, String contextDocument, List<ModelMessage> messages,
                            List<ToolDefinition> tools, int maxOutputTokens, Duration timeout, Map<String, String> metadata) {
            this(systemPrompt, contextDocument, messages, tools, maxOutputTokens, timeout, metadata, ModelTier.STANDARD);
        }
    }

    enum StopReason { END_TURN, TOOL_USE, MAX_TOKENS, CONTENT_FILTERED }

    /**
     * @param inputTokens           tokens de entrada processados sem cache
     * @param outputTokens          tokens gerados
     * @param cacheReadInputTokens  tokens de entrada lidos do cache de prompt do provedor
     * @param cacheWriteInputTokens tokens de entrada gravados no cache de prompt do provedor
     */
    record Usage(long inputTokens, long outputTokens, long cacheReadInputTokens, long cacheWriteInputTokens) {

        public Usage(long inputTokens, long outputTokens) {
            this(inputTokens, outputTokens, 0, 0);
        }

        /** Tokens do prompt (com e sem cache): base dos limites por turno e do limiar de contexto longo. */
        public long promptTokens() {
            return inputTokens + cacheReadInputTokens + cacheWriteInputTokens;
        }
    }

    /** @param amount custo estimado da chamada na moeda do contrato com o provedor */
    record Cost(BigDecimal amount, String currency) {
    }

    /**
     * @param text      resposta em texto (pode vir junto com chamadas de ferramenta)
     * @param toolCalls ferramentas pedidas
     * @param model     identificador do modelo ou perfil de inferência efetivamente usado
     * @param latency   duração da chamada (inclusive a retentativa)
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
