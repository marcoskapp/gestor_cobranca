package br.com.gestorcobranca.analytics.application.port.in.creditor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Indicadores do próprio credor no portal do credor (fatia 7; docs/negocio/indicadores.md §10). Só o ator
 * {@code CREDITOR} com {@code CREDITOR_PORTAL_OVERVIEW}; o credor vem do ator (nunca do cliente). Lê os snapshots do
 * analytics com dimensões fixas (mês e carteira) — nunca por operador, equipe, canal ou origem, nada de comissão,
 * custo ou motivo de suspensão. Valores na data de corte do último refresh ({@code dataUntil}).
 */
public interface CreditorIndicatorsQueries {

    /** @param period THIS_MONTH (padrão), LAST_MONTH, LAST_3_MONTHS ou THIS_YEAR; outro → 422 */
    CreditorIndicators indicators(String period);

    /**
     * @param monthly     os 6 últimos meses (o corrente incluído, parcial), do mais antigo ao mais recente
     * @param byPortfolio as 5 carteiras com mais recuperado no período e "Outros" ({@code others})
     * @param dataUntil   instante a partir do qual os snapshots ainda não refletem as mudanças (nulo: nunca atualizado)
     * @param cached      servido do cache curto (até 60 s, invalidado por um refresh novo)
     */
    record CreditorIndicators(String period, LocalDate from, LocalDate to, LocalDate previousFrom, LocalDate previousTo,
                              CreditorRecovery recovery, List<CreditorMonth> monthly,
                              List<CreditorPortfolioShare> byPortfolio, CreditorBalance balance,
                              CreditorAgreements agreements, Instant dataUntil, boolean cached) {
    }

    /**
     * Recuperado = pagamentos confirmados líquidos de estornos (mesma base da prestação de contas).
     *
     * @param paidToCreditor a parte paga diretamente ao credor (confirmada pela assessoria), já dentro de {@code recovered}
     */
    record CreditorRecovery(BigDecimal recovered, BigDecimal paidToCreditor, BigDecimal previousRecovered,
                            BigDecimal previousPaidToCreditor) {
    }

    /** @param month {@code AAAA-MM} */
    record CreditorMonth(String month, BigDecimal recovered, BigDecimal paidToCreditor) {
    }

    /** @param portfolioId nulo em "Outros" ({@code others = true}) */
    record CreditorPortfolioShare(UUID portfolioId, String name, BigDecimal recovered, boolean others) {
    }

    /** Saldo dos títulos em cobrança (em aberto ou suspensos, com saldo) na data de referência. */
    record CreditorBalance(BigDecimal amount, long titles, LocalDate referenceDate) {
    }

    /**
     * Acordos ativos (formalizados ou em pagamento) sem parcela vencida e não paga — ou com a quebra suspensa por
     * pagamento em análise — ÷ acordos ativos, na data de referência.
     *
     * @param ratio fração (escala 4); nula sem acordo ativo
     * @param alert abaixo de 80%
     */
    record CreditorAgreements(long onTrack, long active, BigDecimal ratio, boolean alert, LocalDate referenceDate) {
    }
}
