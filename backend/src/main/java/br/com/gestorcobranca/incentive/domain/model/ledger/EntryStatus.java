package br.com.gestorcobranca.incentive.domain.model.ledger;

/**
 * Ciclo do lançamento: {@code PENDING} durante a carência (absorve estornos e conciliação);
 * {@code CLEARED} quando liberado para apuração num período; {@code WAIVED} para estorno ocorrido
 * depois do fechamento do período quando o plano não permite estorno de comissão (§5.2) — fica
 * registrado, sem efeito no extrato.
 */
public enum EntryStatus {
    PENDING, CLEARED, WAIVED
}
