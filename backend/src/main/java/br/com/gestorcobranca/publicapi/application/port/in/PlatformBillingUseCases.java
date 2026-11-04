package br.com.gestorcobranca.publicapi.application.port.in;

import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Faturamento da plataforma às assessorias pelo consumo da API pública — segunda camada de cobrança, usada pela equipe
 * da plataforma (tenant da plataforma, {@code PLATFORM_BILLING_VIEW} / {@code PLATFORM_BILLING_MANAGE}). A política de
 * cobrança é por assessoria e <b>independente</b> das condições que a assessoria cobra de cada credor no contrato: a
 * base de cálculo é o consumo somado de todos os tokens da assessoria (inclusive revogados ou expirados), de todos os
 * credores, lido da mesma medição da cobrança ao credor. Não cobra nem emite nota fiscal: deixa o extrato pronto para
 * faturar.
 * <p>
 * Regras compartilhadas com a cobrança ao credor: ciclo fecha no fim do dia de fechamento e o próximo começa às 00:00
 * do dia seguinte (fuso de negócio; meses sem o dia fecham no último dia); ciclos desde o início da vigência, com
 * mensalidade e franquias proporcionais no primeiro, no último e no de transição; só chamadas 2xx/3xx; MB inteiros por
 * token e direção (1 MB = 1.048.576 bytes, para cima); nova versão vale a partir do próximo ciclo e a primeira já no
 * ciclo em andamento; sem cobrança retroativa; extrato imutável com marca do consumo e revisões com motivo.
 * <p>
 * Os dados ficam no tenant da assessoria cobrada; as operações da plataforma trocam o tenant da transação para a
 * assessoria ({@code TenantSwitch}, o mesmo mecanismo do provisionamento), sob RLS.
 */
public interface PlatformBillingUseCases {

    /** Visão consolidada: ciclo em andamento de cada assessoria ativa, receita até agora, projeção e ranking. */
    OverviewView overview();

    PolicyView policy(UUID tenantId);

    /** Primeiro cadastro da política da assessoria: vigência e versão 1 (vale já no ciclo em andamento). */
    PolicyView createPolicy(UUID tenantId, CreatePolicyCommand command);

    /** Nova versão das condições: vale a partir do próximo ciclo. */
    PolicyView addVersion(UUID tenantId, PolicyTermsCommand terms, String reason);

    /** Altera a vigência (o início só enquanto não há extrato; o fim nunca no passado). */
    PolicyView changeTerm(UUID tenantId, LocalDate startDate, LocalDate endDate, String reason);

    PositionView position(UUID tenantId);

    /** Prévia (sem gravar) do próximo ciclo a fechar da assessoria, encerrado ou em curso. */
    StatementView preview(UUID tenantId);

    /**
     * Fecha o próximo ciclo encerrado ({@code periodStart} nulo ou igual ao início dele) ou gera nova revisão de um
     * ciclo já fechado ({@code periodStart} desse ciclo; exige {@code reason}).
     */
    StatementView close(UUID tenantId, LocalDate periodStart, String reason);

    PageResponse<StatementView> statements(UUID tenantId, YearMonth month, PageQuery page);

    StatementView statement(UUID tenantId, UUID statementId);

    /** Extrato em CSV; registra {@code DATA_EXPORTED}. */
    void exportStatement(UUID tenantId, UUID statementId, OutputStream output);

    // ------------------------------------------------------------------ comandos

    /**
     * @param upTo última chamada do ciclo (contagem acumulada) na faixa; {@code null} na última
     */
    record TierView(Long upTo, BigDecimal unitPrice) {
    }

    /**
     * Condições de uma versão da política.
     *
     * @param pricePerRequest preço único por chamada excedente; {@code null} com faixas de volume
     * @param requestTiers    faixas de volume das chamadas (vazio = preço único)
     */
    record PolicyTermsCommand(int closingDay, BigDecimal monthlyFee, long includedRequests, BigDecimal pricePerRequest,
                              List<TierView> requestTiers, long includedMbIn, BigDecimal pricePerMbIn,
                              long includedMbOut, BigDecimal pricePerMbOut, long includedRecords,
                              BigDecimal pricePerRecord) {
    }

    record CreatePolicyCommand(LocalDate startDate, LocalDate endDate, PolicyTermsCommand terms, String reason) {
    }

    // ------------------------------------------------------------------ política

    /**
     * @param appliesToCurrentCycle a versão é a que vale para o ciclo em andamento
     * @param registeredBy          usuário da plataforma que registrou
     */
    record PolicyVersionView(UUID id, int version, int closingDay, BigDecimal monthlyFee, long includedRequests,
                             BigDecimal pricePerRequest, List<TierView> requestTiers, long includedMbIn,
                             BigDecimal pricePerMbIn, long includedMbOut, BigDecimal pricePerMbOut,
                             long includedRecords, BigDecimal pricePerRecord, Instant registeredAt, UUID registeredBy,
                             String reason, boolean appliesToCurrentCycle) {
    }

    /**
     * @param policyId      nulo quando a assessoria ainda não tem política (nada é cobrado)
     * @param versions      da mais recente para a mais antiga
     * @param hasStatements a assessoria já tem extrato da plataforma (o início da vigência fica fixo)
     */
    record PolicyView(UUID tenantId, UUID policyId, LocalDate startDate, LocalDate endDate,
                      List<PolicyVersionView> versions, boolean hasStatements) {
    }

    // ------------------------------------------------------------------ cálculo

    record TierChargeView(long from, Long to, long units, BigDecimal unitPrice, BigDecimal amount) {
    }

    /**
     * @param metric    MONTHLY_FEE, REQUESTS, MB_IN, MB_OUT ou RECORDS
     * @param unitPrice nulo nas chamadas com faixas de volume (ver {@code tiers})
     */
    record ChargeLineView(String metric, long quantity, long included, long billable, BigDecimal unitPrice,
                          BigDecimal amount, List<TierChargeView> tiers) {
    }

    /**
     * Detalhamento: por credor (soma dos tokens do credor), por token (com o credor dono) e por operação (método e
     * rota). Totais: {@code requests}, {@code billableRequests}, {@code billableMbIn}, {@code billableMbOut} (MB
     * arredondados por token; na operação não há MB, que é faturado por token) e {@code records}.
     *
     * @param kind       CREDITOR, TOKEN ou ROUTE
     * @param creditorId credor do token (nulo nos demais)
     */
    record BreakdownView(String kind, String key, String label, UUID creditorId, Map<String, Long> totals) {
    }

    /** @param percent uso da franquia em % (2 casas); nulo sem franquia */
    record AllowanceUse(String metric, long used, long included, BigDecimal percent) {
    }

    /** Acumulado até o fim do dia {@code day} do ciclo, precificado pela política do ciclo. */
    record DailyPoint(int day, LocalDate date, long billableRequests, long billableMbIn, long billableMbOut,
                      long records, BigDecimal amount) {
    }

    /** Projeção pela média diária: atual + atual ÷ dias decorridos × dias restantes (para cima), precificada. */
    record ProjectionView(String method, long billableRequests, long billableMbIn, long billableMbOut, long records,
                          BigDecimal amount) {
    }

    record StatementRef(UUID id, LocalDate periodStart, LocalDate periodEnd, int revision, BigDecimal totalAmount) {
    }

    // ------------------------------------------------------------------ extrato e posição

    /**
     * @param id        nulo na prévia
     * @param revision  0 na prévia
     * @param ended     o ciclo já terminou (pode ser fechado)
     * @param cycleKind REGULAR, POLICY_START (desde o início da vigência), POLICY_END (até o fim da vigência) ou
     *                  TRANSITION (mudança do dia de fechamento)
     * @param creditors credores com consumo no ciclo
     * @param tokens    tokens com consumo no ciclo (inclusive revogados ou expirados)
     * @param closedBy  usuário da plataforma que fechou ou revisou; nulo no fechamento automático
     */
    record StatementView(UUID id, UUID tenantId, LocalDate periodStart, LocalDate periodEnd, boolean ended,
                         int revision, int closingDay, int cycleDays, int referenceCycleDays, String cycleKind,
                         int policyVersion, long requests, long billableRequests, long billableMbIn,
                         long billableMbOut, long records, int creditors, int tokens, BigDecimal totalAmount,
                         String currency, List<ChargeLineView> lines, List<BreakdownView> breakdown,
                         String usageHash, Instant closedAt, UUID closedBy, String reason) {
    }

    /**
     * Ciclo em andamento da assessoria, sem esperar o fechamento.
     *
     * @param hasPolicy     sem política, o ciclo é o mês corrente, o valor é zero e o consumo aparece só como
     *                      informação
     * @param cycleKind     REGULAR, POLICY_START, POLICY_END, TRANSITION ou NO_POLICY
     * @param policyVersion versão aplicada ao ciclo (nula sem política)
     * @param amountSoFar   valor se o ciclo fechasse agora: mensalidade do ciclo (cheia ou proporcional, a mesma do
     *                      fechamento) + excedente consumido até agora
     * @param updatedAt     última gravação em lote do consumo no ciclo
     */
    record PositionView(UUID tenantId, boolean hasPolicy, LocalDate periodStart, LocalDate periodEnd, int closingDay,
                        int cycleDays, int referenceCycleDays, String cycleKind, LocalDate today, int daysElapsed,
                        int daysRemaining, Integer policyVersion, Instant policyRegisteredAt, long requests,
                        long billableRequests, long billableMbIn, long billableMbOut, long records, int creditors,
                        int tokens, BigDecimal amountSoFar, String currency, List<ChargeLineView> lines,
                        List<AllowanceUse> allowances, List<BreakdownView> breakdown, List<DailyPoint> daily,
                        ProjectionView projection, StatementRef lastStatement, Instant updatedAt,
                        long flushIntervalSeconds, long calculationMillis) {
    }

    // ------------------------------------------------------------------ consolidado

    /**
     * @param rank posição no ranking de consumo (chamadas cobráveis do ciclo em andamento; 1 = maior)
     */
    record TenantSummary(UUID tenantId, String slug, String tradeName, boolean hasPolicy, LocalDate periodStart,
                         LocalDate periodEnd, int daysElapsed, int cycleDays, Integer policyVersion,
                         long billableRequests, long billableMbIn, long billableMbOut, long records, int tokens,
                         BigDecimal amountSoFar, BigDecimal projectedAmount, StatementRef lastStatement, int rank) {
    }

    /**
     * @param lastClosedAmount soma do último extrato fechado de cada assessoria
     */
    record OverviewTotals(int tenants, int withPolicy, int withoutPolicy, BigDecimal amountSoFar,
                          BigDecimal projectedAmount, BigDecimal lastClosedAmount, long billableRequests,
                          long billableMbIn, long billableMbOut, long records) {
    }

    /** @param tenants ordenadas pelo ranking de consumo */
    record OverviewView(LocalDate today, OverviewTotals totals, List<TenantSummary> tenants, long calculationMillis) {
    }
}
