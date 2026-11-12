package br.com.gestorcobranca.costing.application.port.in;

import br.com.gestorcobranca.costing.domain.model.ChannelItem;
import br.com.gestorcobranca.costing.domain.model.CostCategory;
import br.com.gestorcobranca.costing.domain.model.MarginBasis;
import br.com.gestorcobranca.costing.domain.model.MarginLine;
import br.com.gestorcobranca.costing.domain.model.ReconciliationStatus;
import com.fasterxml.jackson.annotation.JsonUnwrapped;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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
     * Tendência dos 6 meses que terminam no mês do fim do período ({@code GET /api/v1/costing/margin/trend}, desenho
     * §14.1): mesmos filtros, validação e escopo de {@link #margin(MarginFilter)}; o início do período não muda a
     * janela. Só credores e carteiras com algum fato na janela; sem a linha sem credor.
     */
    MarginTrend trend(MarginFilter filter);

    /**
     * @param from         início (inclusive); nulo com {@code to} nulo = mês anterior fechado
     * @param to           fim (inclusive)
     * @param creditorIds  vazio = todos do escopo
     * @param portfolioIds vazio = todas
     * @param basis        base dos custos de canal; nula = {@code ESTIMATED} (fase 1)
     */
    record MarginFilter(LocalDate from, LocalDate to, Set<UUID> creditorIds, Set<UUID> portfolioIds,
                        MarginBasis basis) {

        public MarginFilter {
            creditorIds = creditorIds == null ? Set.of() : Set.copyOf(creditorIds);
            portfolioIds = portfolioIds == null ? Set.of() : Set.copyOf(portfolioIds);
            basis = basis == null ? MarginBasis.ESTIMATED : basis;
        }

        /** Filtro na base estimada (fase 1). */
        public MarginFilter(LocalDate from, LocalDate to, Set<UUID> creditorIds, Set<UUID> portfolioIds) {
            this(from, to, creditorIds, portfolioIds, null);
        }
    }

    record Period(LocalDate from, LocalDate to) {
    }

    /**
     * @param basis         base dos custos: {@code ESTIMATED} (uso × tarifa) ou {@code RECONCILED} (custos de canal com o
     *                      ajuste pela fatura — telefonia, mensagens e IA)
     * @param totals        soma das linhas visíveis (credores e, para quem vê todos, a linha sem credor)
     * @param unallocated   valores sem credor (ociosidade sem credor no dia, chamada sem credor, custo fixo sem base);
     *                      nulo para escopo restrito ou com filtro de credor/carteira
     * @param months        um item por mês do período (mesmo sem dados), no formato {@code AAAA-MM}
     * @param computedUntil último dia apurado (nulo = nunca apurado)
     * @param refreshedAt   quando a última apuração terminou
     * @param reconciliation situação da conciliação com as faturas de canal no período e o ajuste (§13)
     */
    record MarginReport(Period period, String basis, MarginLine totals, MarginLine unallocated,
                        List<CreditorMargin> creditors, List<MonthMargin> months, List<MarginWarning> warnings,
                        LocalDate computedUntil, Instant refreshedAt, Reconciliation reconciliation) {
    }

    /**
     * Conciliação com as faturas de canal (§13), nas duas bases.
     *
     * @param status      situação do período: {@code ESTIMATED} (nenhum mês com fatura), {@code PARTIAL} ou
     *                    {@code RECONCILED} (todos os meses conciliados)
     * @param months      situação de cada mês do período
     * @param adjustments ajuste pela fatura por categoria de canal ({@code TELEPHONY}, {@code MESSAGING}, {@code AI}),
     *                    somado no mesmo recorte das linhas visíveis (escopo restrito: só os próprios credores, sem a
     *                    linha sem credor); já incluído nos custos quando {@code basis = RECONCILED}
     * @param invoices    faturas do período com a estimativa da assessoria inteira — só para quem vê todos os credores
     *                    e sem filtro de credor ou carteira (os valores da fatura são da assessoria); nulo nos demais
     */
    record Reconciliation(ReconciliationStatus status, List<MonthReconciliation> months,
                          Map<CostCategory, BigDecimal> adjustments, List<InvoiceLine> invoices) {
    }

    /**
     * @param month    {@code AAAA-MM}
     * @param invoiced itens com fatura no mês
     * @param pending  itens com uso estimado no mês ainda sem fatura
     */
    record MonthReconciliation(String month, ReconciliationStatus status, List<ChannelItem> invoiced,
                               List<ChannelItem> pending) {
    }

    /**
     * Fatura de um item num mês ao lado da estimativa da assessoria inteira.
     *
     * @param adjustment fatura − estimativa (rateado na proporção do custo estimado do item)
     */
    record InvoiceLine(String month, ChannelItem item, BigDecimal estimated, BigDecimal invoiced,
                       BigDecimal adjustment) {
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
     * {@code NOT_REFRESHED} (dias do período ainda não apurados), {@code INVOICE_WITHOUT_USAGE} (faturas de canal sem
     * uso no mês; só na base conciliada e para quem vê a linha sem credor).
     */
    record MarginWarning(String code, BigDecimal count) {
    }

    /**
     * @param months        os 6 meses da janela, do mais antigo ao mais recente
     * @param computedUntil último dia apurado (nulo = nunca apurado)
     * @param creditors     credores do escopo com algum fato na janela (ordem pelo id; a tela junta com {@code /margin})
     */
    record MarginTrend(Period period, List<TrendMonth> months, LocalDate computedUntil,
                       List<CreditorTrend> creditors) {
    }

    /** @param status {@code COMPLETE}, {@code PARTIAL} (apurado só até parte do mês) ou {@code NOT_REFRESHED} */
    record TrendMonth(String month, String status) {
    }

    /** @param points um por mês da janela, na mesma ordem de {@code months} */
    record CreditorTrend(UUID creditorId, List<TrendPoint> points, List<PortfolioTrend> portfolios) {
    }

    /** @param portfolioId nulo = custos do credor sem carteira */
    record PortfolioTrend(UUID portfolioId, List<TrendPoint> points) {
    }

    /**
     * Ponto do mês. Mês não apurado ou sem nenhum fato do corte: todos os valores nulos (nunca zero inventado);
     * {@code netMarginRate} nulo sem receita.
     */
    record TrendPoint(String month, BigDecimal recovered, BigDecimal revenue, BigDecimal netMargin,
                      BigDecimal netMarginRate) {
    }
}
