package br.com.gestorcobranca.shared.ai;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Contrato neutro de provedor para um modelo de linguagem com <strong>chamada de ferramentas</strong>
 * (function calling), compartilhado pelos produtos de IA (assistente de negociação, módulo {@code assistant};
 * chat de suporte, módulo {@code helpdesk}). Cada produto declara a própria porta de saída estendendo esta
 * interface (um bean por produto, com prompt, limites e métricas próprios) e escolhe o adaptador: Amazon
 * Bedrock ({@link br.com.gestorcobranca.shared.ai.bedrock.BedrockConverseModel}, Converse API, modelos
 * Claude — aprovado na issue #12) ou um simulado determinístico do próprio produto (local/teste).
 * <p>
 * Regras do contrato (ver docs/tecnico/desenho/assistente.md §6 e docs/tecnico/desenho/chat-suporte.md):
 * <ul>
 *   <li>{@link ModelRequest#systemPrompt()} e {@link ModelRequest#contextDocument()} (contexto minimizado em
 *       JSON) são entregues ao modelo como instruções de sistema, em blocos separados e nunca concatenados ao
 *       texto da pessoa (que só aparece nas mensagens {@code USER});</li>
 *   <li>{@link ModelRequest#tier()} é o nível de modelo escolhido pelo servidor ({@link ModelTier}); o adaptador
 *       o mapeia para o modelo ou perfil de inferência configurado e devolve em {@link ModelResponse#model()} o
 *       identificador efetivamente usado;</li>
 *   <li>o adaptador respeita {@code maxOutputTokens} (sempre enviado ao provedor) e {@code timeout} (tempo
 *       máximo da chamada, inclusive a retentativa) e lança {@link ModelTimeoutException} (tempo esgotado),
 *       {@link ModelUnavailableException} (limite de taxa, 5xx, rede, saída malformada) ou
 *       {@link ModelRejectedException} (recusa definitiva: validação, acesso negado, modelo não habilitado,
 *       cota, janela de contexto); resposta bloqueada pelo filtro de conteúdo do provedor volta como
 *       {@link StopReason#CONTENT_FILTERED};</li>
 *   <li>nenhuma retentativa dentro do adaptador além de <strong>uma</strong> para limite de taxa/5xx imediatos;</li>
 *   <li>{@link Usage} separa os tokens de entrada não cacheados dos lidos e gravados no cache de prompt do
 *       provedor; o custo é calculado pelo adaptador com a tabela de preços da configuração (nunca no código);</li>
 *   <li>a requisição não leva identificadores de pessoa: {@code metadata} só com ids opacos para correlação
 *       no provedor; o provedor precisa estar contratado sem retenção nem treinamento com os dados (no
 *       Bedrock, o provedor do modelo não acessa prompts nem respostas);</li>
 *   <li>credenciais do provedor nunca em {@code application.yml}: no Bedrock, cadeia padrão do SDK (papel IAM
 *       da instância EC2).</li>
 * </ul>
 */
public interface LanguageModel {

    /** Identificador estável do provedor (tag de métricas e trilha). */
    String provider();

    ModelResponse complete(ModelRequest request);

    enum Role { USER, ASSISTANT, TOOL }

    /**
     * @param role        autor: pessoa atendida ({@code USER}), assistente ({@code ASSISTANT}) ou resultado de ferramenta ({@code TOOL})
     * @param content     texto (já minimizado) ou resultado da ferramenta em JSON
     * @param toolCalls   chamadas pedidas pelo assistente nesta mensagem
     * @param toolCallId  chamada respondida por uma mensagem {@code TOOL}
     * @param attachments imagens enviadas junto com a mensagem {@code USER} (leitura visual; ignoradas nos demais papéis)
     */
    record ModelMessage(Role role, String content, List<ToolCall> toolCalls, String toolCallId,
                        List<Attachment> attachments) {

        public ModelMessage {
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
            attachments = attachments == null ? List.of() : List.copyOf(attachments);
        }

        public ModelMessage(Role role, String content, List<ToolCall> toolCalls, String toolCallId) {
            this(role, content, toolCalls, toolCallId, List.of());
        }

        public static ModelMessage user(String content) {
            return new ModelMessage(Role.USER, content, List.of(), null);
        }

        /** Mensagem da pessoa com imagens (ex.: páginas de um PDF sem camada de texto). */
        public static ModelMessage user(String content, List<Attachment> attachments) {
            return new ModelMessage(Role.USER, content, List.of(), null, attachments);
        }

        public static ModelMessage assistant(String content, List<ToolCall> calls) {
            return new ModelMessage(Role.ASSISTANT, content, calls, null);
        }

        public static ModelMessage tool(String callId, String resultJson) {
            return new ModelMessage(Role.TOOL, resultJson, List.of(), callId);
        }
    }

    /**
     * Imagem enviada ao modelo. Pode conter dado pessoal: o produto só a envia quando o texto não basta (PDF
     * escaneado) e nunca a registra em log.
     *
     * @param format formato da imagem ({@code png} ou {@code jpeg})
     */
    record Attachment(String format, byte[] content) {

        public Attachment {
            if (format == null || !(format.equals("png") || format.equals("jpeg"))) {
                throw new IllegalArgumentException("Formato de imagem não suportado: " + format);
            }
            if (content == null || content.length == 0) {
                throw new IllegalArgumentException("Imagem vazia");
            }
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

    /** Falha do provedor (o produto decide o tratamento: transferir para humano, mensagem de indisponível…). */
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
