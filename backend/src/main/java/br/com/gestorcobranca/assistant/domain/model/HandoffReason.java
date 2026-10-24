package br.com.gestorcobranca.assistant.domain.model;

import java.util.Arrays;
import java.util.Optional;

/**
 * Motivos de transferência para humano (camada 3 — docs/negocio/assistente-ia.md §4), com o perfil
 * exigido padrão (roteamento pelo tipo de caso, §5: contestação → sênior; risco jurídico, fraude e
 * falecimento → supervisão; demais → qualquer operador) e se o motivo conta como reclamação nas
 * métricas de qualidade. Os gatilhos obrigatórios não podem ser desligados pela política; ela só
 * acrescenta gatilhos ({@link #POLICY_TRIGGER}) e troca o perfil exigido.
 */
public enum HandoffReason {
    DISPUTE("SENIOR", false, true),
    PAYMENT_CLAIM("SENIOR", false, true),
    FRAUD_SUSPECTED("SUPERVISOR", false, true),
    DECEASED("SUPERVISOR", false, true),
    VULNERABILITY("SENIOR", false, true),
    LEGAL_MENTION("SUPERVISOR", true, true),
    COMPLAINT("SUPERVISOR", true, true),
    ABUSIVE("SUPERVISOR", false, true),
    HUMAN_REQUESTED(null, false, true),
    OPT_OUT(null, true, false),
    LOW_CONFIDENCE(null, false, true),
    TURN_LIMIT(null, false, true),
    IDENTITY_NOT_CONFIRMED(null, false, true),
    REQUIRES_APPROVAL(null, false, true),
    FORMALIZATION_APPROVAL(null, false, true),
    GUARDRAIL(null, false, true),
    COST_LIMIT(null, false, true),
    TECHNICAL_FAILURE(null, false, true),
    ASSISTANT_DISABLED(null, false, true),
    CHANNEL_NOT_ENABLED(null, false, true),
    POLICY_TRIGGER(null, false, true),
    /** o devedor quer pagar, mas o Pix não pode ser gerado pelo assistente (limite, risco, contestação…) */
    PAYMENT_ASSISTANCE(null, false, true),
    MODEL_REQUESTED(null, false, true);

    private final String defaultProfile;
    private final boolean complaint;
    private final boolean notifiesDebtor;

    HandoffReason(String defaultProfile, boolean complaint, boolean notifiesDebtor) {
        this.defaultProfile = defaultProfile;
        this.complaint = complaint;
        this.notifiesDebtor = notifiesDebtor;
    }

    /** Perfil exigido na fila quando a política não define outro (nulo = qualquer operador). */
    public String defaultProfile() {
        return defaultProfile;
    }

    /** Conta como reclamação/risco de reclamação nas métricas de qualidade. */
    public boolean complaint() {
        return complaint;
    }

    /** O devedor recebe a mensagem de transferência (opt-out: nenhum contato depois do pedido). */
    public boolean notifiesDebtor() {
        return notifiesDebtor;
    }

    /**
     * Gatilho de transferência ligado à conduta da conversa (regra, sinal do modelo, guardrail, gatilho da
     * política, identidade não confirmada, pedido de humano ou do modelo). Qualquer um deles na sessão impede
     * o Pix gerado pelo assistente ("conversa amigável e conciliação tranquila"). Limites operacionais,
     * falhas técnicas e encaminhamentos do motor (alçada, formalização) não contam.
     */
    public boolean conversationRisk() {
        return switch (this) {
            case DISPUTE, PAYMENT_CLAIM, FRAUD_SUSPECTED, DECEASED, VULNERABILITY, LEGAL_MENTION, COMPLAINT, ABUSIVE,
                 HUMAN_REQUESTED, OPT_OUT, LOW_CONFIDENCE, IDENTITY_NOT_CONFIRMED, GUARDRAIL, POLICY_TRIGGER,
                 MODEL_REQUESTED -> true;
            default -> false;
        };
    }

    /** Motivos que o modelo pode informar ao pedir a transferência. */
    public boolean selectableByModel() {
        return switch (this) {
            case DISPUTE, PAYMENT_CLAIM, FRAUD_SUSPECTED, DECEASED, VULNERABILITY, LEGAL_MENTION, COMPLAINT, ABUSIVE,
                 HUMAN_REQUESTED, LOW_CONFIDENCE, MODEL_REQUESTED -> true;
            default -> false;
        };
    }

    public static Optional<HandoffReason> parse(String code) {
        if (code == null) {
            return Optional.empty();
        }
        String normalized = code.trim().toUpperCase(java.util.Locale.ROOT);
        return Arrays.stream(values()).filter(r -> r.name().equals(normalized)).findFirst();
    }
}
