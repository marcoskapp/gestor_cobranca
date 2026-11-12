package br.com.gestorcobranca.costing.application.port.in;

import br.com.gestorcobranca.costing.domain.model.MarginLine;
import com.fasterxml.jackson.annotation.JsonUnwrapped;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Painel de margem por credor e carteira (contrato §10.2, {@code GET /api/v1/costing/margin}). Exige
 * {@code COST_VIEW}; o escopo por credor reduz o filtro pedido aos credores do usuário. Quem não vê todos os credores
 * recebe só as linhas dos próprios credores (ociosidade e custos fixos já rateados a eles) e nenhum total da
 * assessoria além da soma delas; as linhas sem credor ({@code unallocated}) só aparecem a quem vê todos os credores e
 * sem filtro de credor ou carteira.
 */
public interface MarginQueries {

    MarginReport margin(MarginFilter filter);

    /**
     * @param from         início (inclusive); nulo com {@code to} nulo = mês anterior fechado
     * @param to           fim (inclusive)
     * @param creditorIds  vazio = todos do escopo
     * @param portfolioIds vazio = todas
     */
    record MarginFilter(LocalDate from, LocalDate to, Set<UUID> creditorIds, Set<UUID> portfolioIds) {

        public MarginFilter {
            creditorIds = creditorIds == null ? Set.of() : Set.copyOf(creditorIds);
            portfolioIds = portfolioIds == null ? Set.of() : Set.copyOf(portfolioIds);
        }
    }

    record Period(LocalDate from, LocalDate to) {
    }

    /**
     * @param basis         base dos custos: {@code ESTIMATED} (uso × tarifa; a conciliação com a fatura é da fase 2)
     * @param totals        soma das linhas visíveis (credores e, para quem vê todos, a linha sem credor)
     * @param unallocated   valores sem credor (ociosidade sem credor no dia, chamada sem credor, custo fixo sem base);
     *                      nulo para escopo restrito ou com filtro de credor/carteira
     * @param months        um item por mês do período (mesmo sem dados), no formato {@code AAAA-MM}
     * @param computedUntil último dia apurado (nulo = nunca apurado)
     * @param refreshedAt   quando a última apuração terminou
     */
    record MarginReport(Period period, String basis, MarginLine totals, MarginLine unallocated,
                        List<CreditorMargin> creditors, List<MonthMargin> months, List<MarginWarning> warnings,
                        LocalDate computedUntil, Instant refreshedAt) {
    }

    record CreditorMargin(UUID creditorId, String name, @JsonUnwrapped MarginLine line,
                          List<PortfolioMargin> portfolios) {
    }

    /** @param portfolioId nulo = custos do credor sem carteira (ex.: custo fixo direto ao credor) */
    record PortfolioMargin(UUID portfolioId, String name, @JsonUnwrapped MarginLine line) {
    }

    record MonthMargin(String month, @JsonUnwrapped MarginLine line) {
    }

    /**
     * Aviso com a quantidade: {@code HOURS_WITHOUT_BAND} (horas), {@code MISSING_FX_RATE} e {@code NO_FX_RATE} (meses),
     * {@code MISSING_TARIFF} (minutos, conversas ou mensagens), {@code OVERHEAD_WITHOUT_BASIS} (custos fixos),
     * {@code NOT_REFRESHED} (dias do período ainda não apurados).
     */
    record MarginWarning(String code, BigDecimal count) {
    }
}
