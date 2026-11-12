package br.com.gestorcobranca.costing.domain.model;

import java.util.Collection;
import java.util.Set;

/**
 * Situação da conciliação de um mês com as faturas de canal (desenho §13): {@link #ESTIMATED} = nenhuma fatura
 * informada; {@link #PARTIAL} = parte dos itens com uso estimado tem fatura; {@link #RECONCILED} = todos os itens com
 * uso estimado no mês têm fatura (ou o mês não tem uso e tem alguma fatura).
 */
@org.springframework.modulith.NamedInterface("usecases")
public enum ReconciliationStatus {
    ESTIMATED,
    PARTIAL,
    RECONCILED;

    /**
     * @param withUsage itens com uso estimado no mês (valor ou quantidade)
     * @param invoiced  itens com fatura informada no mês
     */
    public static ReconciliationStatus of(Set<ChannelItem> withUsage, Set<ChannelItem> invoiced) {
        if (invoiced.isEmpty()) {
            return ESTIMATED;
        }
        return invoiced.containsAll(withUsage) ? RECONCILED : PARTIAL;
    }

    /** Situação de um período: todos os meses iguais → a mesma; mistura → parcial; sem meses → estimado. */
    public static ReconciliationStatus combine(Collection<ReconciliationStatus> months) {
        if (months.isEmpty()) {
            return ESTIMATED;
        }
        if (months.stream().allMatch(s -> s == RECONCILED)) {
            return RECONCILED;
        }
        return months.stream().allMatch(s -> s == ESTIMATED) ? ESTIMATED : PARTIAL;
    }
}
