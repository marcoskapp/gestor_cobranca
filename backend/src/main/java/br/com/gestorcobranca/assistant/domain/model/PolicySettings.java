package br.com.gestorcobranca.assistant.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Parâmetros de uma versão da política do assistente (esquema v1, guardado em jsonb). Valores
 * nulos recebem o padrão em {@link #normalized()}; a validação recusa combinações inseguras.
 *
 * @param mode                  modo de operação
 * @param channels              canais atendidos (hoje somente WHATSAPP: o communication só aceita texto
 *                              livre no WhatsApp; e-mail e SMS exigem template)
 * @param serviceHours          janelas de atendimento no fuso de operação (vazio = sempre)
 * @param authorityProfileId    perfil de alçada do negotiation (nulo = padrão {@code ASSISTENTE_INICIAL})
 * @param promptCode            prompt versionado usado (recurso do módulo)
 * @param promptVersion         versão do prompt
 * @param tone                  FORMAL ou PROXIMO
 * @param maxTurns              turnos do assistente na conversa antes de oferecer humano
 * @param maxMisunderstandings  falhas seguidas de entendimento (baixa confiança) antes de transferir
 * @param minConfidence         confiança mínima (0–1) informada pelo modelo para seguir sozinho
 * @param requireIdentityVerification confirma a identidade (3 primeiros dígitos do CPF + nascimento) antes de revelar a dívida
 * @param maxIdentityAttempts   tentativas de confirmação antes de transferir
 * @param allowedTopics         temas que o assistente trata (informados ao modelo)
 * @param forbiddenTopics       termos que nunca podem aparecer na resposta (guardrail)
 * @param extraTriggers         gatilhos adicionais de transferência (os obrigatórios são fixos)
 * @param requiredProfiles      perfil exigido por motivo (substitui o padrão do motivo)
 * @param identificationMessage apresentação obrigatória como assistente virtual (transparência)
 * @param handoffMessage        mensagem ao transferir para humano
 * @param fallbackMessage       mensagem quando a resposta é recusada pelo guardrail ou há falha
 * @param approvalMessage       mensagem quando o aceite aguarda a formalização por humano
 * @param limits                limites de tokens, ferramentas, tempo e custo
 * @param reviewSampleRate      fração dos turnos amostrada para revisão humana (0–1)
 * @param allowCreditBureauMention a resposta pode mencionar negativação (só se autorizada pelo credor)
 */
public record PolicySettings(
        AssistantMode mode,
        Set<String> channels,
        List<ServiceWindow> serviceHours,
        UUID authorityProfileId,
        String promptCode,
        Integer promptVersion,
        String tone,
        Integer maxTurns,
        Integer maxMisunderstandings,
        BigDecimal minConfidence,
        Boolean requireIdentityVerification,
        Integer maxIdentityAttempts,
        List<String> allowedTopics,
        List<String> forbiddenTopics,
        List<ExtraTrigger> extraTriggers,
        Map<String, String> requiredProfiles,
        String identificationMessage,
        String handoffMessage,
        String fallbackMessage,
        String approvalMessage,
        Limits limits,
        BigDecimal reviewSampleRate,
        Boolean allowCreditBureauMention) {

    public static final int SCHEMA_VERSION = 1;
    public static final Set<String> SUPPORTED_CHANNELS = Set.of("WHATSAPP");
    public static final String DEFAULT_PROMPT = "negociacao";
    public static final String DEFAULT_IDENTIFICATION = "Olá! Sou a assistente virtual de cobrança e vou te ajudar por aqui. "
            + "Se preferir falar com um atendente humano, é só escrever HUMANO a qualquer momento.";
    public static final String DEFAULT_HANDOFF = "Vou transferir seu atendimento para um de nossos atendentes, que "
            + "continuará a conversa por aqui. Obrigado pela paciência.";
    public static final String DEFAULT_FALLBACK = "Desculpe, não consegui concluir sua solicitação. Um de nossos "
            + "atendentes vai continuar o atendimento por aqui.";
    public static final String DEFAULT_APPROVAL = "Seu aceite foi registrado. Um de nossos atendentes vai confirmar a "
            + "formalização do acordo e enviar os próximos passos por aqui.";

    /** Janela semanal de atendimento (fim exclusivo; início = fim = dia inteiro). */
    public record ServiceWindow(List<DayOfWeek> days, LocalTime start, LocalTime end) {
    }

    /**
     * @param keyword    expressão (comparada sem acentos e sem diferenciar maiúsculas)
     * @param reasonCode motivo registrado (padrão {@code POLICY_TRIGGER})
     */
    public record ExtraTrigger(String keyword, String reasonCode) {
    }

    /**
     * @param maxOutputTokens        tokens de saída por chamada ao modelo
     * @param maxTokensPerTurn       tokens (entrada + saída) somados no turno
     * @param maxToolCallsPerTurn    chamadas de ferramenta por turno
     * @param timeoutSeconds         tempo máximo de cada chamada ao modelo
     * @param maxCostPerConversation custo acumulado do modelo por conversa (moeda do provedor)
     */
    public record Limits(Integer maxOutputTokens, Integer maxTokensPerTurn, Integer maxToolCallsPerTurn,
                         Integer timeoutSeconds, BigDecimal maxCostPerConversation) {
    }

    /** Política mínima usada como modelo de cadastro. */
    public static PolicySettings defaults(AssistantMode mode) {
        return new PolicySettings(mode, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null).normalized();
    }

    /** Cópia com os padrões aplicados e as regras de segurança validadas. */
    public PolicySettings normalized() {
        Guard.require(mode != null, "assistant_policy.mode_required", "Modo de operação obrigatório");
        Set<String> ch = new LinkedHashSet<>();
        for (String c : channels == null || channels.isEmpty() ? Set.of("WHATSAPP") : channels) {
            String code = c == null ? "" : c.trim().toUpperCase(Locale.ROOT);
            Guard.require(SUPPORTED_CHANNELS.contains(code), "assistant_policy.channel_unsupported",
                    "Canal não suportado pelo assistente: " + c + " (somente WHATSAPP — e-mail e SMS exigem template)");
            ch.add(code);
        }
        List<ServiceWindow> hours = new ArrayList<>();
        for (ServiceWindow w : serviceHours == null ? List.<ServiceWindow>of() : serviceHours) {
            Guard.require(w != null && w.days() != null && !w.days().isEmpty() && w.start() != null && w.end() != null,
                    "assistant_policy.hours_invalid", "Janela de atendimento exige dias, início e fim");
            Guard.require(!w.start().isAfter(w.end()), "assistant_policy.hours_invalid",
                    "Janela de atendimento não pode atravessar a meia-noite (use duas janelas)");
            hours.add(new ServiceWindow(List.copyOf(new LinkedHashSet<>(w.days())), w.start(), w.end()));
        }
        String t = tone == null || tone.isBlank() ? "FORMAL" : tone.trim().toUpperCase(Locale.ROOT);
        Guard.require(t.equals("FORMAL") || t.equals("PROXIMO"), "assistant_policy.tone_invalid", "Tom: FORMAL ou PROXIMO");
        int turns = maxTurns == null ? 12 : maxTurns;
        Guard.require(turns >= 1 && turns <= 50, "assistant_policy.max_turns_invalid", "Turnos máximos entre 1 e 50");
        int misunderstandings = maxMisunderstandings == null ? 2 : maxMisunderstandings;
        Guard.require(misunderstandings >= 1 && misunderstandings <= 5, "assistant_policy.max_misunderstandings_invalid",
                "Falhas de entendimento entre 1 e 5");
        BigDecimal confidence = minConfidence == null ? new BigDecimal("0.60") : minConfidence;
        Guard.require(confidence.signum() >= 0 && confidence.compareTo(BigDecimal.ONE) <= 0,
                "assistant_policy.min_confidence_invalid", "Confiança mínima entre 0 e 1");
        boolean identity = requireIdentityVerification == null || requireIdentityVerification;
        int identityAttempts = maxIdentityAttempts == null ? 3 : maxIdentityAttempts;
        Guard.require(identityAttempts >= 1 && identityAttempts <= 5, "assistant_policy.identity_attempts_invalid",
                "Tentativas de identificação entre 1 e 5");
        List<String> allowed = terms(allowedTopics, "assistant_policy.topic_invalid");
        List<String> forbidden = terms(forbiddenTopics, "assistant_policy.topic_invalid");
        List<ExtraTrigger> extras = new ArrayList<>();
        for (ExtraTrigger e : extraTriggers == null ? List.<ExtraTrigger>of() : extraTriggers) {
            Guard.require(e != null && e.keyword() != null && e.keyword().trim().length() >= 3
                    && e.keyword().trim().length() <= 60, "assistant_policy.trigger_invalid",
                    "Gatilho adicional com expressão de 3 a 60 caracteres");
            HandoffReason reason = e.reasonCode() == null || e.reasonCode().isBlank() ? HandoffReason.POLICY_TRIGGER
                    : HandoffReason.parse(e.reasonCode()).orElseThrow(() -> new br.com.gestorcobranca.shared.domain
                    .BusinessRuleException("assistant_policy.trigger_reason_invalid", "Motivo inválido: " + e.reasonCode()));
            extras.add(new ExtraTrigger(e.keyword().trim(), reason.name()));
        }
        Map<String, String> profiles = new LinkedHashMap<>();
        if (requiredProfiles != null) {
            requiredProfiles.forEach((k, v) -> {
                HandoffReason reason = HandoffReason.parse(k).orElseThrow(() -> new br.com.gestorcobranca.shared.domain
                        .BusinessRuleException("assistant_policy.profile_reason_invalid", "Motivo inválido: " + k));
                Guard.require(v == null || v.matches("[A-Za-z0-9_]{1,40}"), "assistant_policy.profile_invalid",
                        "Perfil exigido inválido para " + k);
                if (v != null && !v.isBlank()) {
                    profiles.put(reason.name(), v.trim().toUpperCase(Locale.ROOT));
                }
            });
        }
        String identification = text(identificationMessage, DEFAULT_IDENTIFICATION, "identification");
        String plain = plain(identification);
        Guard.require(plain.contains("assistente virtual") && (plain.contains("humano") || plain.contains("atendente")),
                "assistant_policy.transparency_required",
                "A apresentação precisa dizer que é um assistente virtual e como pedir atendimento humano");
        String handoff = text(handoffMessage, DEFAULT_HANDOFF, "handoff");
        String fallback = text(fallbackMessage, DEFAULT_FALLBACK, "fallback");
        String approval = text(approvalMessage, DEFAULT_APPROVAL, "approval");
        Limits l = limits == null ? new Limits(null, null, null, null, null) : limits;
        Limits lim = new Limits(bounded(l.maxOutputTokens(), 600, 50, 4000, "max_output_tokens"),
                bounded(l.maxTokensPerTurn(), 8000, 500, 64000, "max_tokens_per_turn"),
                bounded(l.maxToolCallsPerTurn(), 6, 1, 12, "max_tool_calls"),
                bounded(l.timeoutSeconds(), 20, 2, 60, "timeout"),
                l.maxCostPerConversation() == null ? new BigDecimal("1.00") : l.maxCostPerConversation());
        Guard.require(lim.maxCostPerConversation().signum() > 0, "assistant_policy.max_cost_invalid",
                "Custo máximo por conversa deve ser positivo");
        BigDecimal sample = reviewSampleRate == null ? new BigDecimal("0.10") : reviewSampleRate;
        Guard.require(sample.signum() >= 0 && sample.compareTo(BigDecimal.ONE) <= 0, "assistant_policy.sample_rate_invalid",
                "Taxa de amostragem entre 0 e 1");
        String prompt = promptCode == null || promptCode.isBlank() ? DEFAULT_PROMPT : promptCode.trim().toLowerCase(Locale.ROOT);
        Guard.require(prompt.matches("[a-z0-9-]{2,40}"), "assistant_policy.prompt_invalid", "Código de prompt inválido");
        int pv = promptVersion == null ? 1 : promptVersion;
        Guard.require(pv >= 1, "assistant_policy.prompt_invalid", "Versão de prompt inválida");
        return new PolicySettings(mode, Set.copyOf(ch), List.copyOf(hours), authorityProfileId, prompt, pv, t, turns,
                misunderstandings, confidence, identity, identityAttempts, allowed, forbidden, List.copyOf(extras),
                Map.copyOf(profiles), identification, handoff, fallback, approval, lim, sample,
                allowCreditBureauMention != null && allowCreditBureauMention);
    }

    /** Perfil exigido na fila para o motivo (política → padrão do motivo). */
    public String requiredProfileFor(HandoffReason reason) {
        String configured = requiredProfiles == null ? null : requiredProfiles.get(reason.name());
        return configured != null ? configured : reason.defaultProfile();
    }

    private static List<String> terms(List<String> values, String code) {
        List<String> out = new ArrayList<>();
        for (String v : values == null ? List.<String>of() : values) {
            Guard.require(v != null && !v.isBlank() && v.trim().length() <= 60, code, "Tema com até 60 caracteres");
            out.add(v.trim());
        }
        Guard.require(out.size() <= 50, code, "No máximo 50 temas");
        return List.copyOf(out);
    }

    private static String text(String value, String fallback, String field) {
        String v = value == null || value.isBlank() ? fallback : value.trim();
        Guard.require(v.length() <= 600, "assistant_policy.message_too_long", "Mensagem " + field + " até 600 caracteres");
        return v;
    }

    private static int bounded(Integer value, int fallback, int min, int max, String field) {
        int v = value == null ? fallback : value;
        Guard.require(v >= min && v <= max, "assistant_policy.limit_invalid",
                "Limite %s entre %d e %d".formatted(field, min, max));
        return v;
    }

    private static String plain(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }
}
