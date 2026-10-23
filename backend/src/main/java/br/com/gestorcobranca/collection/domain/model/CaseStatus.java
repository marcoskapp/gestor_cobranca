package br.com.gestorcobranca.collection.domain.model;

import java.util.EnumSet;
import java.util.Set;

/**
 * Ciclo de vida do caso de cobrança. Há dois grupos de estados:
 * <ul>
 *   <li><b>estágios de tratamento</b> — {@code OPEN → IN_NEGOTIATION → PROMISE | AGREEMENT}:
 *       mostram o andamento da negociação e mudam livremente entre si;</li>
 *   <li><b>retenções</b> — {@code DISPUTED}, {@code SUSPENDED}, {@code LEGAL}: tiram o caso da
 *       operação regular. Ao entrar numa retenção, o estágio corrente é guardado e restaurado na
 *       saída; mudanças de estágio durante a retenção (ex.: acordo formalizado) atualizam o
 *       estágio guardado. Prioridade: LEGAL &gt; SUSPENDED &gt; DISPUTED (uma retenção de menor
 *       prioridade não substitui outra maior).</li>
 * </ul>
 * {@code CLOSED} é final: se o devedor voltar a ter títulos em aberto na carteira, um novo caso
 * é aberto.
 */
public enum CaseStatus {
    /** Em cobrança, sem contato efetivo ainda. */
    OPEN,
    /** Houve contato efetivo; negociação em andamento. */
    IN_NEGOTIATION,
    /** Promessa de pagamento pendente. */
    PROMISE,
    /** Acordo formalizado em vigência (módulo negotiation). */
    AGREEMENT,
    /** Contestação aberta que bloqueia a cobrança (F21). */
    DISPUTED,
    /** Suspenso manualmente (pedido do credor, análise). */
    SUSPENDED,
    /** Encaminhado ao jurídico. */
    LEGAL,
    /** Encerrado. */
    CLOSED;

    private static final Set<CaseStatus> STAGES = EnumSet.of(OPEN, IN_NEGOTIATION, PROMISE, AGREEMENT);
    private static final Set<CaseStatus> HOLDS = EnumSet.of(DISPUTED, SUSPENDED, LEGAL);

    public boolean isStage() {
        return STAGES.contains(this);
    }

    public boolean isHold() {
        return HOLDS.contains(this);
    }

    public boolean isActive() {
        return this != CLOSED;
    }

    /** Estados em que o caso pode ser entregue a um operador pela fila. */
    public boolean isWorkable() {
        return this == OPEN || this == IN_NEGOTIATION || this == PROMISE;
    }

    /** Prioridade da retenção (0 para estágios e CLOSED). */
    public int holdPriority() {
        return switch (this) {
            case LEGAL -> 3;
            case SUSPENDED -> 2;
            case DISPUTED -> 1;
            default -> 0;
        };
    }

    public static Set<CaseStatus> stages() {
        return EnumSet.copyOf(STAGES);
    }

    public static Set<CaseStatus> workable() {
        return EnumSet.of(OPEN, IN_NEGOTIATION, PROMISE);
    }
}
