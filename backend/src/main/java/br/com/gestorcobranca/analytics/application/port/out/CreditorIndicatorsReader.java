package br.com.gestorcobranca.analytics.application.port.out;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Leituras dos snapshots do analytics para um único credor (portal do credor, fatia 7), sob o RLS da assessoria. Toda
 * consulta de dado tem {@code creditor_id = :creditorId} literal — sem o filtro genérico do {@link Slice} — e só
 * agrupa por mês ou carteira. Devolve medidas aditivas; as regras ficam no domínio.
 */
public interface CreditorIndicatorsReader {

    /** Recebido e estornado no intervalo (inclusive), no total e só o pago diretamente ao credor. */
    RecoveryTotals recovery(UUID creditorId, LocalDate from, LocalDate to);

    /** Os mesmos totais por mês (primeiro dia do mês), só os meses com movimento. */
    List<MonthTotals> recoveryByMonth(UUID creditorId, LocalDate from, LocalDate to);

    /** Recebido e estornado por carteira (chave nula = recebimento sem carteira). */
    List<PortfolioTotals> recoveryByPortfolio(UUID creditorId, LocalDate from, LocalDate to);

    /** Rótulo ({@code código — nome}) das carteiras do próprio credor. */
    Map<UUID, String> portfolioNames(UUID creditorId, Collection<UUID> portfolioIds);

    BalanceTotals balance(UUID creditorId);

    /** Acordos ativos agrupados por (tem parcela vencida e não paga, quebra suspensa). */
    List<AgreementStanding> activeAgreements(UUID creditorId);

    /** "Dados até" da assessoria: o menor carimbo dos conjuntos usados (recuperação, safra, carteira); nulo sem refresh. */
    Instant dataUntil();

    /** Instante do último refresh da assessoria (invalida o cache curto); nulo sem refresh. */
    Instant lastRefreshedAt();

    record RecoveryTotals(BigDecimal gross, BigDecimal reversed, BigDecimal creditorGross, BigDecimal creditorReversed) {
    }

    record MonthTotals(LocalDate month, RecoveryTotals totals) {
    }

    record PortfolioTotals(UUID portfolioId, BigDecimal gross, BigDecimal reversed) {
    }

    record BalanceTotals(BigDecimal amount, long titles, LocalDate referenceDate) {
    }

    record AgreementStanding(boolean hasOverdueInstallment, boolean breakSuspended, long agreements) {
    }
}
