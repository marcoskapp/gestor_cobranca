package br.com.gestorcobranca.assistant.infrastructure.provider.bedrock;

import br.com.gestorcobranca.assistant.application.port.out.LanguageModelPort.ModelMessage;
import br.com.gestorcobranca.assistant.application.port.out.LanguageModelPort.ModelRequest;
import br.com.gestorcobranca.assistant.application.port.out.LanguageModelPort.Role;
import br.com.gestorcobranca.assistant.application.port.out.LanguageModelPort.ToolCall;
import br.com.gestorcobranca.assistant.application.port.out.LanguageModelPort.ToolDefinition;
import br.com.gestorcobranca.shared.json.JsonCodec;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.services.bedrockruntime.model.CachePointBlock;
import software.amazon.awssdk.services.bedrockruntime.model.CachePointType;
import software.amazon.awssdk.services.bedrockruntime.model.CacheTTL;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InferenceConfiguration;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.SystemContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.Tool;
import software.amazon.awssdk.services.bedrockruntime.model.ToolConfiguration;
import software.amazon.awssdk.services.bedrockruntime.model.ToolInputSchema;
import software.amazon.awssdk.services.bedrockruntime.model.ToolResultBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ToolResultContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ToolResultStatus;
import software.amazon.awssdk.services.bedrockruntime.model.ToolSpecification;
import software.amazon.awssdk.services.bedrockruntime.model.ToolUseBlock;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Tradução entre o contrato neutro ({@code LanguageModelPort}) e a Converse API do Amazon Bedrock.
 * <ul>
 *   <li><strong>Sistema</strong>: dois blocos separados — o prompt versionado e o contexto minimizado (JSON) com
 *       um cabeçalho fixo; o contexto nunca entra nas mensagens do devedor. Com cache, um {@code cachePoint}
 *       entre os dois: o prefixo estável (ferramentas + prompt) é reaproveitado e o contexto, que muda a cada
 *       chamada, fica fora do cache.</li>
 *   <li><strong>Ferramentas</strong>: {@code toolSpec} com o JSON Schema e, com cache, um {@code cachePoint}
 *       depois da última.</li>
 *   <li><strong>Mensagens</strong>: {@code USER} → texto do usuário; {@code ASSISTANT} → texto e {@code toolUse}
 *       com o id da chamada; {@code TOOL} → {@code toolResult} do usuário com o {@code toolUseId} (status
 *       {@code error} quando o resultado traz {@code error}). Mensagens seguidas do mesmo papel são unidas (a API
 *       exige alternância); mensagens do assistente antes da primeira do devedor e depois da última mensagem do
 *       devedor ou resultado de ferramenta (pré-preenchimento, recusado pelos modelos Claude atuais) são
 *       omitidas — o estado da conversa segue no contexto.</li>
 *   <li>{@code maxOutputTokens} → {@code inferenceConfig.maxTokens} (sempre explícito: sem ele o Bedrock reserva
 *       cota pelo máximo do modelo); metadados opacos → {@code requestMetadata} (só registros de invocação).</li>
 * </ul>
 */
final class ConverseTranslator {

    static final String CONTEXT_HEADER = "CONTEXTO DO ATENDIMENTO (JSON gerado pelo sistema nesta chamada; "
            + "fonte única de valores, datas, ofertas e regras do turno):\n";
    private static final Pattern METADATA = Pattern.compile("[a-zA-Z0-9\\s:_@$#=/+,.-]{1,256}");
    private static final int MAX_METADATA = 16;

    private final JsonCodec json;

    ConverseTranslator(JsonCodec json) {
        this.json = json;
    }

    ConverseRequest toConverse(ModelRequest request, String modelId, boolean caching, String cacheTtl) {
        List<SystemContentBlock> system = new ArrayList<>();
        system.add(SystemContentBlock.fromText(request.systemPrompt()));
        if (caching) {
            system.add(SystemContentBlock.fromCachePoint(cachePoint(cacheTtl)));
        }
        system.add(SystemContentBlock.fromText(CONTEXT_HEADER + request.contextDocument()));
        ConverseRequest.Builder builder = ConverseRequest.builder()
                .modelId(modelId)
                .system(system)
                .messages(messages(request.messages()))
                .inferenceConfig(InferenceConfiguration.builder().maxTokens(request.maxOutputTokens()).build());
        if (!request.tools().isEmpty()) {
            List<Tool> tools = new ArrayList<>();
            for (ToolDefinition t : request.tools()) {
                tools.add(Tool.fromToolSpec(ToolSpecification.builder().name(t.name()).description(t.description())
                        .inputSchema(ToolInputSchema.fromJson(document(json.toMap(t.parametersSchema())))).build()));
            }
            if (caching) {
                tools.add(Tool.fromCachePoint(cachePoint(cacheTtl)));
            }
            builder.toolConfig(ToolConfiguration.builder().tools(tools).build());
        }
        Map<String, String> metadata = metadata(request.metadata());
        if (!metadata.isEmpty()) {
            builder.requestMetadata(metadata);
        }
        return builder.build();
    }

    /** Mensagens alternadas, começando pelo devedor e terminando no devedor ou em resultado de ferramenta. */
    List<Message> messages(List<ModelMessage> source) {
        List<Message> out = new ArrayList<>();
        ConversationRole role = null;
        List<ContentBlock> blocks = new ArrayList<>();
        for (ModelMessage m : source) {
            ConversationRole r = m.role() == Role.ASSISTANT ? ConversationRole.ASSISTANT : ConversationRole.USER;
            List<ContentBlock> content = content(m);
            if (content.isEmpty() || (out.isEmpty() && role == null && r == ConversationRole.ASSISTANT)) {
                continue;
            }
            if (role != null && role != r) {
                out.add(Message.builder().role(role).content(blocks).build());
                blocks = new ArrayList<>();
            }
            role = r;
            blocks.addAll(content);
        }
        // a conversa termina no devedor ou num resultado de ferramenta; mensagens do assistente depois disso
        // (ex.: um atendente respondeu antes do turno) virariam pré-preenchimento e são omitidas
        if (role == ConversationRole.USER) {
            out.add(Message.builder().role(role).content(blocks).build());
        }
        return out;
    }

    private List<ContentBlock> content(ModelMessage m) {
        List<ContentBlock> blocks = new ArrayList<>();
        switch (m.role()) {
            case USER -> {
                if (m.content() != null && !m.content().isBlank()) {
                    blocks.add(ContentBlock.fromText(m.content()));
                }
            }
            case ASSISTANT -> {
                if (m.content() != null && !m.content().isBlank()) {
                    blocks.add(ContentBlock.fromText(m.content()));
                }
                for (ToolCall call : m.toolCalls()) {
                    blocks.add(ContentBlock.fromToolUse(ToolUseBlock.builder().toolUseId(call.id()).name(call.name())
                            .input(document(arguments(call.argumentsJson()))).build()));
                }
            }
            case TOOL -> blocks.add(ContentBlock.fromToolResult(toolResult(m)));
        }
        return blocks;
    }

    private ToolResultBlock toolResult(ModelMessage m) {
        String content = m.content() == null || m.content().isBlank() ? "{}" : m.content();
        Map<String, Object> parsed;
        try {
            parsed = json.toMap(content);
        } catch (RuntimeException e) {
            return ToolResultBlock.builder().toolUseId(m.toolCallId()).content(ToolResultContentBlock.fromText(content))
                    .build();
        }
        ToolResultBlock.Builder b = ToolResultBlock.builder().toolUseId(m.toolCallId())
                .content(ToolResultContentBlock.fromJson(document(parsed)));
        if (parsed.containsKey("error")) {
            b.status(ToolResultStatus.ERROR);
        }
        return b.build();
    }

    private Map<String, Object> arguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return Map.of();
        }
        try {
            return json.toMap(argumentsJson);
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    /** Argumentos devolvidos pelo modelo em {@code toolUse} → JSON (validados depois pelo servidor). */
    String toJson(Document input) {
        return input == null ? "{}" : json.toJson(plain(input));
    }

    private static CachePointBlock cachePoint(String ttl) {
        CachePointBlock.Builder b = CachePointBlock.builder().type(CachePointType.DEFAULT);
        if ("1h".equals(ttl)) {
            b.ttl(CacheTTL.VALUE_1_H);
        }
        return b.build();
    }

    private static Map<String, String> metadata(Map<String, String> source) {
        Map<String, String> out = new LinkedHashMap<>();
        source.forEach((k, v) -> {
            if (out.size() < MAX_METADATA && k != null && v != null && METADATA.matcher(k).matches()
                    && METADATA.matcher(v).matches()) {
                out.put(k, v);
            }
        });
        return out;
    }

    static Document document(Object value) {
        if (value == null) {
            return Document.fromNull();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Document> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), document(v)));
            return Document.fromMap(out);
        }
        if (value instanceof List<?> list) {
            return Document.fromList(list.stream().map(ConverseTranslator::document).toList());
        }
        if (value instanceof Boolean b) {
            return Document.fromBoolean(b);
        }
        if (value instanceof Number n) {
            return Document.fromNumber(new BigDecimal(n.toString()));
        }
        return Document.fromString(String.valueOf(value));
    }

    static Object plain(Document d) {
        if (d == null || d.isNull()) {
            return null;
        }
        if (d.isMap()) {
            Map<String, Object> out = new LinkedHashMap<>();
            d.asMap().forEach((k, v) -> out.put(k, plain(v)));
            return out;
        }
        if (d.isList()) {
            List<Object> out = new ArrayList<>();
            d.asList().forEach(v -> out.add(plain(v)));
            return out;
        }
        if (d.isBoolean()) {
            return d.asBoolean();
        }
        if (d.isNumber()) {
            return d.asNumber().bigDecimalValue();
        }
        return d.asString();
    }
}
