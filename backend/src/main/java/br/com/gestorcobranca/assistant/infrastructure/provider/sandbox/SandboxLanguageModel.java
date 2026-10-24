package br.com.gestorcobranca.assistant.infrastructure.provider.sandbox;

import br.com.gestorcobranca.assistant.application.AssistantProperties;
import br.com.gestorcobranca.assistant.application.port.out.LanguageModelPort;
import br.com.gestorcobranca.shared.json.JsonCodec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.Normalizer;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Modelo de linguagem <strong>simulado e determinístico</strong> para ambiente local e testes
 * ({@code app.assistant.sandbox.enabled=true}; recusado no perfil {@code prod} por
 * {@code AssistantSandboxGuard}). Nenhuma rede. Segue um roteiro por intenção e palavras-chave, usando
 * as ferramentas como um modelo real faria (sempre {@code register_intent} primeiro):
 * <ul>
 *   <li>saldo/quanto devo → {@code get_debt_summary} e responde o saldo do motor;</li>
 *   <li>negociar/parcelar/à vista/"Nx" → {@code simulate_options} (parcelas pedidas; "desconto" pede o
 *       nível 1 com motivo {@code IMMEDIATE_PAYMENT}; "[sandbox:nivel:N]" pede o nível N) e lista as ofertas;</li>
 *   <li>aceito/fechar (opcional "O2") → {@code propose_offer} e, se a proposta ficou {@code DRAFT},
 *       {@code register_acceptance};</li>
 *   <li>Pix/pagar agora (com as ferramentas de Pix disponíveis) → {@code get_installments} e
 *       {@code generate_payment} da parcela pedida ("I2") ou da primeira;</li>
 *   <li>identidade pendente → pede os 3 primeiros dígitos do CPF e a data de nascimento.</li>
 * </ul>
 * Marcadores para testes: {@code [sandbox:confuso]} (confiança 0,2), {@code [sandbox:sinal:<MOTIVO>]}
 * (sinal de transferência do modelo), {@code [sandbox:valor-inventado]} (resposta com valor fora do motor,
 * para o guardrail), {@code [sandbox:timeout]} e {@code [sandbox:indisponivel]} (falhas do provedor),
 * {@code [sandbox:transferir]} (o modelo pede humano). Tokens estimados por caracteres/4 e custo pelas
 * tarifas simuladas de {@code app.assistant.sandbox}.
 */
@Component
@ConditionalOnProperty(prefix = "app.assistant.sandbox", name = "enabled", havingValue = "true")
class SandboxLanguageModel implements LanguageModelPort {

    static final String MODEL = "sandbox-roteiro-v1";
    private static final DateTimeFormatter BR_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final Pattern OFFER_REF = Pattern.compile("\\b[oO](\\d{1,2})\\b");
    private static final Pattern TIMES = Pattern.compile("(\\d{1,2})\\s?(?:x|vezes|parcelas)");
    private static final Pattern LEVEL = Pattern.compile("\\[sandbox:nivel:(\\d)]");
    private static final Pattern SIGNAL = Pattern.compile("\\[sandbox:sinal:([a-z_]+)]");
    private static final Pattern INSTALLMENT_REF = Pattern.compile("\\b[iI](\\d{1,2})\\b");

    private final JsonCodec json;
    private final AssistantProperties.Sandbox tariff;

    SandboxLanguageModel(JsonCodec json, AssistantProperties properties) {
        this.json = json;
        this.tariff = properties.sandbox();
    }

    @Override
    public String provider() {
        return "sandbox";
    }

    @Override
    public ModelResponse complete(ModelRequest request) {
        ModelMessage lastUser = null;
        for (ModelMessage m : request.messages()) {
            if (m.role() == Role.USER) {
                lastUser = m;
            }
        }
        String text = lastUser == null ? "" : lastUser.content();
        String plain = plain(text);
        if (plain.contains("[sandbox:timeout]")) {
            throw new ModelTimeoutException("Tempo esgotado simulado", null);
        }
        if (plain.contains("[sandbox:indisponivel]")) {
            throw new ModelUnavailableException("Provedor indisponível simulado", null);
        }
        Map<String, Object> context = json.toMap(request.contextDocument());
        Set<String> tools = request.tools().stream().map(ToolDefinition::name).collect(Collectors.toSet());
        List<ModelMessage> tail = sinceLastUser(request.messages());
        Step step = next(plain, context, tools, tail);
        long in = (request.systemPrompt().length() + request.contextDocument().length()
                + request.messages().stream().mapToLong(m -> m.content() == null ? 0 : m.content().length()).sum()) / 4;
        long out = Math.max(1, (step.text() == null ? 40 : step.text().length()) / 4);
        BigDecimal cost = tariff.costPer1kInput().multiply(BigDecimal.valueOf(in))
                .add(tariff.costPer1kOutput().multiply(BigDecimal.valueOf(out)))
                .divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_EVEN);
        return new ModelResponse(step.text(), step.calls(), step.calls().isEmpty() ? StopReason.END_TURN : StopReason.TOOL_USE,
                new Usage(in, out), new Cost(cost, tariff.currency()), MODEL, Duration.ofMillis(5));
    }

    private record Step(String text, List<ToolCall> calls) {

        static Step say(String text) {
            return new Step(text, List.of());
        }

        static Step call(String name, Map<String, Object> args, JsonCodec json) {
            return new Step(null, List.of(new ToolCall("sbx-" + UUID.randomUUID(), name, json.toJson(args))));
        }
    }

    private Step next(String plain, Map<String, Object> context, Set<String> tools, List<ModelMessage> tail) {
        List<String> called = new ArrayList<>();
        Map<String, Map<String, Object>> results = new LinkedHashMap<>();
        Map<String, String> callNames = new LinkedHashMap<>();
        for (ModelMessage m : tail) {
            if (m.role() == Role.ASSISTANT) {
                m.toolCalls().forEach(c -> callNames.put(c.id(), c.name()));
            } else if (m.role() == Role.TOOL) {
                String name = callNames.get(m.toolCallId());
                called.add(name);
                results.put(name, json.toMap(m.content()));
            }
        }
        String intent = intent(plain);
        if (called.isEmpty()) {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("intent", intent);
            args.put("confidence", plain.contains("[sandbox:confuso]") ? 0.2 : 0.9);
            Matcher s = SIGNAL.matcher(plain);
            if (s.find()) {
                args.put("signals", List.of(s.group(1).toUpperCase(Locale.ROOT)));
            }
            return Step.call("register_intent", args, json);
        }
        Map<String, Object> intentResult = results.get("register_intent");
        if (intentResult != null && Boolean.FALSE.equals(intentResult.get("understood"))) {
            return Step.say("Desculpe, não entendi. Você pode explicar de outra forma?");
        }
        if (plain.contains("[sandbox:transferir]") && tools.contains("transfer_to_human") && !called.contains("transfer_to_human")) {
            return Step.call("transfer_to_human", Map.of("reason", "MODEL_REQUESTED", "summary", "pedido do roteiro de teste"), json);
        }
        if (plain.contains("[sandbox:valor-inventado]")) {
            return Step.say("Consigo fazer por R$ 1,00 hoje mesmo.");
        }
        String identity = String.valueOf(context.get("identity"));
        if ("PENDING".equals(identity) || "FAILED".equals(identity)) {
            int left = context.get("attemptsLeft") instanceof Number n ? n.intValue() : 0;
            String retry = intent.equals("IDENTITY") ? "Os dados não conferem. " : "";
            return Step.say(retry + "Para sua segurança, informe os 3 primeiros dígitos do seu CPF e a sua data de "
                    + "nascimento (dia/mês/ano)." + (left <= 1 && !retry.isEmpty() ? " Esta é a última tentativa." : ""));
        }
        switch (intent) {
            case "PAYMENT" -> {
                if (!tools.contains("generate_payment")) {
                    return Step.say("Um de nossos atendentes pode te ajudar com o pagamento por aqui.");
                }
                if (!called.contains("get_installments")) {
                    return Step.call("get_installments", Map.of(), json);
                }
                if (!called.contains("generate_payment")) {
                    Object raw = results.get("get_installments").get("installments");
                    List<?> list = raw instanceof List<?> l ? l : List.of();
                    if (list.isEmpty()) {
                        return Step.say("Não encontrei parcela em aberto de acordo para gerar o Pix.");
                    }
                    Matcher ref = INSTALLMENT_REF.matcher(plain);
                    String installment = ref.find() ? "I" + ref.group(1) : String.valueOf(((Map<?, ?>) list.getFirst()).get("ref"));
                    return Step.call("generate_payment", Map.of("installment", installment), json);
                }
                Map<String, Object> pix = results.get("generate_payment");
                if (Boolean.TRUE.equals(pix.get("issued"))) {
                    return Step.say("Certo! O Pix de " + money(pix.get("amount")) + " foi gerado e vale até "
                            + LocalDate.parse(String.valueOf(pix.get("validUntilDate"))).format(BR_DATE)
                            + ". O código chega em seguida, em mensagem separada.");
                }
                if (Boolean.TRUE.equals(pix.get("suggested"))) {
                    return Step.say("O atendente vai enviar o Pix de " + money(pix.get("amount")) + " por aqui.");
                }
                return Step.say("Se quiser pagar agora, é só confirmar, por exemplo: quero pagar agora. "
                        + "Se preferir, um atendente pode ajudar.");
            }
            case "IDENTITY" -> {
                return Step.say("Obrigado, seus dados foram confirmados. Posso informar o saldo atualizado ou simular "
                        + "opções de pagamento.");
            }
            case "BALANCE" -> {
                if (!called.contains("get_debt_summary")) {
                    return Step.call("get_debt_summary", Map.of(), json);
                }
                Map<String, Object> r = results.get("get_debt_summary");
                if (r.containsKey("error")) {
                    return Step.say("Não consegui consultar o saldo agora.");
                }
                return Step.say("O saldo atualizado da sua dívida é " + money(r.get("updatedTotal"))
                        + ". Posso simular opções de pagamento para você?");
            }
            case "NEGOTIATE" -> {
                if (!called.contains("simulate_options")) {
                    Map<String, Object> args = new LinkedHashMap<>();
                    args.put("installments", installments(plain));
                    Matcher lv = LEVEL.matcher(plain);
                    int level = lv.find() ? Integer.parseInt(lv.group(1)) : plain.contains("desconto") ? 1 : 0;
                    args.put("level", level);
                    if (level > 0) {
                        args.put("reason", "IMMEDIATE_PAYMENT");
                    }
                    return Step.call("simulate_options", args, json);
                }
                return Step.say(describeOffers(results.get("simulate_options")));
            }
            case "ACCEPT" -> {
                if (!called.contains("propose_offer")) {
                    Matcher ref = OFFER_REF.matcher(plain);
                    String offer = ref.find() ? "O" + ref.group(1) : firstOffer(context);
                    if (offer == null) {
                        return Step.say("Antes de fechar, posso simular as opções de pagamento disponíveis?");
                    }
                    return Step.call("propose_offer", Map.of("offer", offer), json);
                }
                Map<String, Object> proposal = results.get("propose_offer");
                if (proposal.containsKey("error")) {
                    return Step.say("Não foi possível registrar essa opção. Posso simular novamente?");
                }
                if (!called.contains("register_acceptance") && "DRAFT".equals(proposal.get("status"))) {
                    return Step.call("register_acceptance", Map.of("proposal", proposal.get("proposal")), json);
                }
                Map<String, Object> accepted = results.get("register_acceptance");
                if (accepted == null || accepted.containsKey("error")) {
                    return Step.say("Registrei a proposta. Para confirmar, responda: aceito.");
                }
                return Step.say("Acordo formalizado: total de " + money(accepted.get("total"))
                        + ". Você receberá as instruções de pagamento por aqui.");
            }
            default -> {
                return Step.say("Estou aqui para ajudar com a sua negociação. Posso informar o saldo atualizado ou "
                        + "simular opções de pagamento.");
            }
        }
    }

    private static String describeOffers(Map<String, Object> result) {
        if (result == null || result.containsKey("error")) {
            return "Não consegui simular agora. Posso tentar outra forma de pagamento?";
        }
        Object raw = result.get("offers");
        List<?> offers = raw instanceof List<?> l ? l : List.of();
        if (offers.isEmpty()) {
            return "Não encontrei opção para essa forma de pagamento. Prefere outra quantidade de pagamentos?";
        }
        StringBuilder sb = new StringBuilder("Estas são as opções disponíveis:");
        for (Object o : offers) {
            Map<?, ?> m = (Map<?, ?>) o;
            int n = ((Number) m.get("installments")).intValue();
            sb.append("\n").append(m.get("ref")).append(": ");
            if (n == 1) {
                sb.append("à vista, ").append(money(m.get("total")));
            } else {
                sb.append(n).append(" pagamentos, total de ").append(money(m.get("total")));
            }
            sb.append(", 1º vencimento em ").append(LocalDate.parse(String.valueOf(m.get("firstDueDate"))).format(BR_DATE))
                    .append('.');
        }
        sb.append("\nPara fechar, responda: aceito a opção desejada (ex.: aceito O1).");
        return sb.toString();
    }

    private static String firstOffer(Map<String, Object> context) {
        Object raw = context.get("offers");
        if (raw instanceof List<?> l && !l.isEmpty()) {
            return String.valueOf(((Map<?, ?>) l.getFirst()).get("ref"));
        }
        return null;
    }

    private static List<Integer> installments(String plain) {
        List<Integer> out = new ArrayList<>();
        if (plain.contains("a vista") || plain.contains("avista")) {
            out.add(1);
        }
        Matcher m = TIMES.matcher(plain);
        while (m.find() && out.size() < 3) {
            out.add(Integer.parseInt(m.group(1)));
        }
        return out.isEmpty() ? List.of(1) : out;
    }

    private static String intent(String plain) {
        if (plain.contains("[digitos omitidos]") || plain.contains("[data omitida]") || plain.contains("[documento omitido]")) {
            return "IDENTITY";
        }
        if (plain.matches(".*\\b(pix|copia e cola|pagar agora|pagar hoje)\\b.*")) {
            return "PAYMENT";
        }
        if (plain.matches(".*\\b(aceito|fechado|pode fechar|fechar o acordo|confirmo)\\b.*")) {
            return "ACCEPT";
        }
        if (plain.matches(".*\\b(negociar|parcel\\w*|a vista|avista|desconto|acordo|proposta|\\d{1,2}\\s?x|opcoes)\\b.*")) {
            return "NEGOTIATE";
        }
        if (plain.matches(".*\\b(saldo|quanto devo|valor da divida|quanto (e|esta))\\b.*")) {
            return "BALANCE";
        }
        if (plain.matches(".*\\b(oi|ola|bom dia|boa tarde|boa noite)\\b.*")) {
            return "GREETING";
        }
        return "QUESTION";
    }

    private static List<ModelMessage> sinceLastUser(List<ModelMessage> messages) {
        int last = -1;
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).role() == Role.USER) {
                last = i;
            }
        }
        return messages.subList(last + 1, messages.size());
    }

    private static String money(Object value) {
        BigDecimal v = new BigDecimal(String.valueOf(value)).setScale(2, RoundingMode.HALF_EVEN);
        DecimalFormat f = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.of("pt", "BR")));
        return "R$ " + f.format(v);
    }

    private static String plain(String s) {
        return Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
    }
}
