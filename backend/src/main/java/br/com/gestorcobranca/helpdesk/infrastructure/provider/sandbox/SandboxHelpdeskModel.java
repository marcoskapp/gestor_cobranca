package br.com.gestorcobranca.helpdesk.infrastructure.provider.sandbox;

import br.com.gestorcobranca.helpdesk.application.HelpdeskProperties;
import br.com.gestorcobranca.helpdesk.application.port.out.HelpdeskModelPort;
import br.com.gestorcobranca.shared.json.JsonCodec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Modelo simulado <strong>determinístico</strong> do chat de suporte, para local e testes
 * ({@code app.helpdesk.sandbox.enabled=true}; recusado no perfil {@code prod} e junto com o Bedrock pelo
 * {@code HelpdeskProviderGuard}). Usa as ferramentas como um modelo real, por palavras-chave da última pergunta,
 * sempre com {@code registrar_pergunta} na primeira rodada; na segunda, resume os resultados. Nenhuma rede.
 * <p>
 * Marcadores de teste: {@code [sandbox:indisponivel]}, {@code [sandbox:timeout]}, {@code [sandbox:filtrado]},
 * {@code [sandbox:fora]}, {@code [sandbox:tool:<nome> <json>]} (pede qualquer ferramenta, inclusive uma não
 * oferecida), {@code [tela:<chave>]} (ABRIR a tela), {@code [sandbox:nao-resolvida]}.
 */
@Component
@ConditionalOnProperty(prefix = "app.helpdesk.sandbox", name = "enabled", havingValue = "true")
class SandboxHelpdeskModel implements HelpdeskModelPort {

    static final String PROVIDER = "sandbox";
    private static final Pattern TOOL = Pattern.compile("\\[sandbox:tool:([a-z_]+)\\s*(\\{.*?})?]");
    private static final Pattern SCREEN = Pattern.compile("\\[tela:([a-z0-9.-]+)]");
    private static final Pattern REF = Pattern.compile("\\b([DCAPL])(\\d{1,3})\\b");
    private static final Pattern DOC = Pattern.compile("\\[DOC\\d{1,3}]");
    private static final Pattern RESULT_REF = Pattern.compile("\"ref\":\"([A-Z]\\d{1,3})\"");

    private final HelpdeskProperties.Sandbox settings;
    private final JsonCodec json;

    SandboxHelpdeskModel(HelpdeskProperties properties, JsonCodec json) {
        this.settings = properties.sandbox();
        this.json = json;
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public ModelResponse complete(ModelRequest request) {
        List<ModelMessage> messages = request.messages();
        int lastUser = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).role() == Role.USER) {
                lastUser = i;
                break;
            }
        }
        String question = lastUser < 0 ? "" : messages.get(lastUser).content();
        String q = question.toLowerCase(Locale.ROOT);
        if (q.contains("[sandbox:indisponivel]")) {
            throw new ModelUnavailableException("Simulador: provedor indisponível", null);
        }
        if (q.contains("[sandbox:timeout]")) {
            throw new ModelTimeoutException("Simulador: tempo esgotado", null);
        }
        List<ModelMessage> results = messages.subList(lastUser + 1, messages.size()).stream()
                .filter(m -> m.role() == Role.TOOL).toList();
        Set<String> available = new LinkedHashSet<>();
        request.tools().forEach(t -> available.add(t.name()));
        if (!results.isEmpty()) {
            return respond(request, summary(question, results), List.of());
        }
        if (q.contains("[sandbox:filtrado]")) {
            return new ModelResponse(null, List.of(), StopReason.CONTENT_FILTERED, usage(request, ""), cost(request, ""),
                    "sandbox-helpdesk", Duration.ofMillis(1));
        }
        List<ToolCall> calls = new ArrayList<>();
        boolean outOfScope = q.contains("[sandbox:fora]") || q.contains("receita de bolo");
        boolean unresolved = q.contains("[sandbox:nao-resolvida]") || q.contains("chamado");
        calls.add(call(1, "registrar_pergunta", Map.of("tema", outOfScope ? "OUT_OF_SCOPE" : topic(q),
                "resolvida", !outOfScope && !unresolved)));
        if (outOfScope) {
            return respond(request, "Só consigo ajudar com o uso do Gestor de Cobrança.", calls);
        }
        Matcher tool = TOOL.matcher(question);
        if (tool.find()) {
            calls.add(new ToolCall("c2", tool.group(1), tool.group(2) == null ? "{}" : tool.group(2)));
            return respond(request, null, calls);
        }
        Matcher screen = SCREEN.matcher(question);
        if (screen.find()) {
            calls.add(call(2, "indicar_tela", Map.of("tela", screen.group(1), "acao", "ABRIR")));
        }
        Map<String, String> refs = refs(question);
        String currentScreen = currentScreen(request.contextDocument());
        if (q.contains("chamado") && available.contains("abrir_chamado")) {
            calls.add(call(3, "abrir_chamado", Map.of("titulo", "Problema relatado no chat de suporte",
                    "resumo", "Relato da pessoa: " + question.replaceAll("\\s+", " ").strip())));
        } else if (q.contains("pendênc") || q.contains("pendenc")) {
            calls.add(call(3, "minhas_pendencias", Map.of()));
        } else if (q.contains("importa")) {
            calls.add(call(3, "status_importacao", refs.containsKey("L") ? Map.of("importacao", refs.get("L")) : Map.of()));
        } else if (q.contains("demonstrativo")) {
            calls.add(call(3, "demonstrativos", Map.of()));
        } else if (q.contains("extrato")) {
            calls.add(call(3, "meus_extratos", Map.of()));
        } else if (q.contains("pagamento") && (refs.containsKey("D") || refs.containsKey("A"))) {
            calls.add(call(3, "pagamentos", refs.containsKey("A") ? Map.of("acordo", refs.get("A"))
                    : Map.of("devedor", refs.get("D"))));
        } else if (q.contains("acordo") && (refs.containsKey("D") || refs.containsKey("C"))) {
            calls.add(call(3, "situacao_acordos", refs.containsKey("C") ? Map.of("caso", refs.get("C"))
                    : Map.of("devedor", refs.get("D"))));
        } else if (q.contains("caso") && refs.containsKey("D")) {
            calls.add(call(3, "situacao_casos", Map.of("devedor", refs.get("D"))));
        } else if (q.contains("devedor") || DOC.matcher(question).find()) {
            Matcher doc = DOC.matcher(question);
            String text = doc.find() ? doc.group() : question.replaceAll("(?i).*devedor\\s+", "").strip();
            calls.add(call(3, "buscar_devedor", Map.of("texto", text)));
        } else if (q.contains("tour") && currentScreen != null) {
            calls.add(call(3, "indicar_tela", Map.of("tela", currentScreen, "acao", "TOUR")));
        } else if (currentScreen != null) {
            calls.add(call(3, "consultar_manual", Map.of("tela", currentScreen)));
        }
        return respond(request, null, calls);
    }

    private static String topic(String q) {
        if (q.contains("erro") || q.contains("chamado")) {
            return "SYSTEM_ERROR";
        }
        if (q.contains("acesso") || q.contains("permiss")) {
            return "ACCESS";
        }
        if (q.contains("devedor") || q.contains("caso") || q.contains("acordo") || q.contains("pagamento") || q.contains("importa")
                || q.contains("pendênc") || q.contains("extrato") || q.contains("demonstrativo") || q.contains("[doc")) {
            return "DATA_LOOKUP";
        }
        return "HOW_TO";
    }

    private String summary(String question, List<ModelMessage> results) {
        String q = question.toLowerCase(Locale.ROOT);
        if (q.contains("[sandbox:fora]") || q.contains("receita de bolo")) {
            return "Só consigo ajudar com o uso do Gestor de Cobrança.";
        }
        List<String> refs = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        String objective = null;
        boolean ticket = false;
        boolean pointed = false;
        for (ModelMessage r : results) {
            String c = r.content() == null ? "" : r.content();
            Matcher m = RESULT_REF.matcher(c);
            while (m.find()) {
                if (!refs.contains(m.group(1))) {
                    refs.add(m.group(1));
                }
            }
            Map<String, Object> parsed = json.toMap(c.isBlank() ? "{}" : c);
            if (parsed.containsKey("error") && !"registrado".equals(parsed.get("error"))) {
                errors.add(String.valueOf(parsed.get("error")));
            }
            ticket |= Boolean.TRUE.equals(parsed.get("preparado"));
            pointed |= Boolean.TRUE.equals(parsed.get("indicado"));
            if (parsed.get("objetivo") instanceof String o) {
                objective = o;
            }
        }
        if (ticket) {
            return "Preparei o chamado com o seu relato. Confira o texto antes de enviar; o retorno será dado por e-mail.";
        }
        StringBuilder sb = new StringBuilder();
        if (!errors.isEmpty()) {
            sb.append("Não consegui consultar: ").append(String.join(", ", errors)).append(". ");
        }
        if (!refs.isEmpty()) {
            sb.append("Encontrei: ").append(String.join(", ", refs)).append(" (detalhes nos cartões abaixo).");
        } else if (objective != null) {
            sb.append("Nesta tela: ").append(objective);
        } else if (pointed) {
            sb.append("Use o botão abaixo.");
        } else if (errors.isEmpty()) {
            sb.append("Consultei o que pediu e não há registros para mostrar.");
        }
        if (q.contains("[sandbox:valor-inventado]")) {
            sb.append(" CPF 529.982.247-25, e-mail maria@exemplo.com.");
        }
        return sb.toString().strip();
    }

    private static Map<String, String> refs(String question) {
        Map<String, String> out = new java.util.HashMap<>();
        Matcher m = REF.matcher(question);
        while (m.find()) {
            out.putIfAbsent(m.group(1), m.group(1) + m.group(2));
        }
        return out;
    }

    private String currentScreen(String context) {
        try {
            Object tela = json.toMap(context).get("telaAtual");
            return tela instanceof Map<?, ?> map && map.get("tela") instanceof String s ? s : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private ToolCall call(int n, String name, Map<String, Object> args) {
        return new ToolCall("c" + n, name, json.toJson(args));
    }

    private ModelResponse respond(ModelRequest request, String text, List<ToolCall> calls) {
        String out = text == null ? "" : text;
        String produced = out + calls.stream().map(ToolCall::argumentsJson).reduce("", String::concat);
        String answer = calls.isEmpty() ? out : (text == null ? null : text);
        return new ModelResponse(answer, calls, calls.isEmpty() ? StopReason.END_TURN : StopReason.TOOL_USE,
                usage(request, produced), cost(request, produced), "sandbox-helpdesk", Duration.ofMillis(5));
    }

    private Usage usage(ModelRequest request, String produced) {
        long in = (request.systemPrompt().length() + request.contextDocument().length()
                + request.messages().stream().mapToLong(m -> m.content() == null ? 0 : m.content().length()).sum()) / 4;
        return new Usage(in, Math.max(1, produced.length() / 4));
    }

    private Cost cost(ModelRequest request, String produced) {
        Usage u = usage(request, produced);
        BigDecimal value = settings.costPer1kInput().multiply(BigDecimal.valueOf(u.inputTokens()))
                .add(settings.costPer1kOutput().multiply(BigDecimal.valueOf(u.outputTokens())))
                .divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_EVEN);
        return new Cost(value, settings.currency());
    }
}
