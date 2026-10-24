package br.com.gestorcobranca.assistant.domain.model;

/**
 * Critérios objetivos de "conversa amigável e conciliação tranquila" avaliados no servidor antes de o
 * assistente gerar um Pix (docs/negocio/assistente-ia.md §10), na ordem de avaliação. Cada um tem o
 * código de recusa devolvido ao modelo e registrado na trilha, e se a recusa leva a um atendente
 * ({@link HandoffReason#PAYMENT_ASSISTANCE}).
 */
public enum PixCriterion {
    /** a política liga o Pix na conversa ({@code pixInConversation.enabled}) */
    POLICY_ENABLED("PIX_DISABLED", false),
    /** modo autônomo (gera) ou sombra/copiloto (só sugere ao operador) */
    MODE_ALLOWED("MODE_NOT_ALLOWED", false),
    /** identidade do devedor confirmada nesta sessão (rascunho: o operador confere) */
    IDENTITY_VERIFIED("IDENTITY_NOT_VERIFIED", false),
    /** acordo formalizado e vigente (FORMALIZED/IN_PAYMENT) */
    AGREEMENT_FORMALIZED("NO_FORMALIZED_AGREEMENT", false),
    /** acordo do mesmo devedor, credor e carteira da conversa */
    SAME_PARTIES("OTHER_PARTY", false),
    /** parcela/entrada com saldo em aberto */
    INSTALLMENT_OPEN("INSTALLMENT_NOT_OPEN", false),
    /** modo com aprovação: só acordo formalizado por humano */
    HUMAN_FORMALIZED("NOT_FORMALIZED_BY_HUMAN", true),
    /** nenhum gatilho de transferência, guardrail, sinal de abuso/ameaça/reclamação ou vulnerabilidade na sessão */
    NO_CONVERSATION_RISK("CONVERSATION_RISK", true),
    /** nenhuma contestação aberta no caso */
    NO_OPEN_DISPUTE("OPEN_DISPUTE", true),
    /** o devedor pediu ou aceitou pagar agora com palavras explícitas nesta mensagem */
    EXPLICIT_REQUEST("NO_EXPLICIT_REQUEST", false),
    /** valor da parcela até o limite da política */
    AMOUNT_WITHIN_LIMIT("AMOUNT_ABOVE_LIMIT", true),
    /** Pix enviados na conversa no dia abaixo do limite da política */
    DAILY_LIMIT("DAILY_LIMIT_REACHED", true);

    private final String denialCode;
    private final boolean handsOff;

    PixCriterion(String denialCode, boolean handsOff) {
        this.denialCode = denialCode;
        this.handsOff = handsOff;
    }

    public String denialCode() {
        return denialCode;
    }

    /** A recusa por este critério transfere para um atendente concluir o pagamento. */
    public boolean handsOff() {
        return handsOff;
    }
}
