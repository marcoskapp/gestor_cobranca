package br.com.gestorcobranca.publicapi.web.v1;

import br.com.gestorcobranca.publicapi.application.port.in.PublicApiBillingUseCases.BillingPositionView;
import br.com.gestorcobranca.publicapi.application.port.in.PublicApiBillingUseCases.StatementBreakdown;
import br.com.gestorcobranca.publicapi.application.port.in.PublicApiBillingUseCases.StatementLine;
import br.com.gestorcobranca.publicapi.application.port.in.PublicApiBillingUseCases.StatementView;
import br.com.gestorcobranca.settlement.application.port.in.SettlementPositionUseCases.DailyPoint;
import br.com.gestorcobranca.settlement.application.port.in.SettlementPositionUseCases.PositionView;
import br.com.gestorcobranca.settlement.application.port.in.SettlementStatementUseCases.TotalsView;
import br.com.gestorcobranca.settlement.application.port.in.TransferUseCases.StatementBalance;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Contrato público v1 do faturamento da API e do repasse ao credor. Minimização: sem identificação de quem fechou,
 * revisou ou aprovou, sem motivos internos, sem memória de cálculo interna (ids de recebimentos, divergências), sem
 * dados de devedores — só valores e períodos do próprio credor.
 */
final class BillingModels {

    private BillingModels() {
    }

    @Schema(name = "BillingLine", description = "Linha do extrato: quantidades inteiras (MB = 1.048.576 bytes)")
    record LineDto(@Schema(allowableValues = {"MONTHLY_FEE", "REQUESTS", "MB_IN", "MB_OUT", "RECORDS"}) String metric,
                   long quantity, long included, long billable, BigDecimal unitPrice, BigDecimal amount) {
        static LineDto of(StatementLine l) {
            return new LineDto(l.metric(), l.quantity(), l.included(), l.billable(), l.unitPrice(), l.amount());
        }
    }

    @Schema(name = "BillingBreakdown", description = "Consumo por token (MB arredondados no token) ou por rota")
    record BreakdownDto(@Schema(allowableValues = {"TOKEN", "ROUTE"}) String kind, String key, String label,
                        Map<String, Long> totals) {
        static BreakdownDto of(StatementBreakdown b) {
            return new BreakdownDto(b.kind(), b.key(), b.label(), b.totals());
        }
    }

    @Schema(name = "BillingStatement", description = "Extrato fechado de um ciclo de consumo da API")
    record StatementDto(UUID id, LocalDate periodStart, LocalDate periodEnd, int revision, int usageClosingDay,
                        @Schema(description = "Dias do ciclo") int cycleDays,
                        @Schema(description = "Dias do ciclo regular de referência; difere de cycleDays só no ciclo "
                                + "de transição (mensalidade e franquias proporcionais)") int referenceCycleDays,
                        String contractNumber, Integer pricingVersion, long requests, long billableRequests,
                        long billableMbIn, long billableMbOut, long records, BigDecimal totalAmount, String currency,
                        List<LineDto> lines, List<BreakdownDto> breakdown, String usageHash, Instant closedAt) {
        static StatementDto of(StatementView s) {
            return new StatementDto(s.id(), s.periodStart(), s.periodEnd(), s.revision(), s.usageClosingDay(),
                    s.cycleDays(), s.referenceCycleDays(), s.contractNumber(), s.pricingVersion(), s.requests(),
                    s.billableRequests(), s.billableMbIn(), s.billableMbOut(), s.records(), s.totalAmount(),
                    s.currency(), s.lines().stream().map(LineDto::of).toList(),
                    s.breakdown().stream().map(BreakdownDto::of).toList(), s.usageHash(), s.closedAt());
        }
    }

    @Schema(name = "BillingGrowthPoint", description = "Acumulado até o fim do dia do ciclo")
    record GrowthDto(int day, LocalDate date, long billableRequests, long billableMbIn, long billableMbOut, long records,
                     BigDecimal amount) {
    }

    @Schema(name = "BillingPosition", description = "Ciclo de consumo em andamento: consumo faturável e valor até agora")
    record BillingPositionDto(LocalDate periodStart, LocalDate periodEnd, int usageClosingDay, int cycleDays,
                              int referenceCycleDays, int daysElapsed, int daysRemaining, String contractNumber,
                              Integer pricingVersion, long requests, long billableRequests, long billableMbIn,
                              long billableMbOut, long records,
                              @Schema(description = "Mensalidade do ciclo + excedente do consumo até agora")
                              BigDecimal amountSoFar, String currency, List<LineDto> lines,
                              List<AllowanceDto> allowances, List<BreakdownDto> breakdown, List<GrowthDto> daily,
                              LocalDate previousPeriodStart, LocalDate previousPeriodEnd, BigDecimal previousTotalAmount,
                              List<GrowthDto> previousDaily,
                              @Schema(description = "Projeção pela média diária até o fim do ciclo")
                              BigDecimal projectedAmount,
                              @Schema(description = "Última gravação do consumo (lotes de ~1 minuto)") Instant updatedAt) {
        static BillingPositionDto of(BillingPositionView p) {
            return new BillingPositionDto(p.periodStart(), p.periodEnd(), p.usageClosingDay(), p.cycleDays(),
                    p.referenceCycleDays(), p.daysElapsed(), p.daysRemaining(), p.contractNumber(), p.pricingVersion(),
                    p.requests(), p.billableRequests(), p.billableMbIn(), p.billableMbOut(), p.records(),
                    p.amountSoFar(), p.currency(), p.lines().stream().map(LineDto::of).toList(),
                    p.allowances().stream().map(a -> new AllowanceDto(a.metric(), a.used(), a.included(), a.percent()))
                            .toList(),
                    p.breakdown().stream().map(BreakdownDto::of).toList(),
                    p.daily().stream().map(g -> new GrowthDto(g.day(), g.date(), g.billableRequests(), g.billableMbIn(),
                            g.billableMbOut(), g.records(), g.amount())).toList(),
                    p.previous().periodStart(), p.previous().periodEnd(), p.previous().totalAmount(),
                    p.previousDaily().stream().map(g -> new GrowthDto(g.day(), g.date(), g.billableRequests(),
                            g.billableMbIn(), g.billableMbOut(), g.records(), g.amount())).toList(),
                    p.projection().amount(), p.updatedAt());
        }
    }

    @Schema(name = "BillingAllowance", description = "Uso da franquia do ciclo")
    record AllowanceDto(String metric, long used, long included, BigDecimal percent) {
    }

    @Schema(name = "SettlementTotals", description = "Totais do repasse (positivo: a assessoria repassa ao credor)")
    record SettlementTotalsDto(BigDecimal receivedByAgency, BigDecimal receivedByCreditor, BigDecimal reversed,
                               BigDecimal recovered, BigDecimal agencyHonorarium, BigDecimal commission,
                               BigDecimal fixedFees, BigDecimal expenses, BigDecimal adjustments,
                               BigDecimal netTransfer, BigDecimal transferred, BigDecimal outstanding,
                               @Schema(description = "Recebimentos aguardando conciliação (fora do líquido)")
                               BigDecimal pendingReconciliation,
                               @Schema(description = "Recebimentos sem regra de comissão vigente (fora do líquido)")
                               BigDecimal pendingRules) {
        static SettlementTotalsDto of(TotalsView t) {
            return new SettlementTotalsDto(t.receivedByAgency(), t.receivedByCreditor(), t.reversed(), t.recovered(),
                    t.agencyHonorarium(), t.commission(), t.fixedFees(), t.expenses(), t.adjustments(), t.netTransfer(),
                    t.transferred(), t.outstanding(), t.pendingReconciliation(), t.pendingRules());
        }
    }

    @Schema(name = "SettlementDailyPoint")
    record DailyDto(int day, LocalDate date, BigDecimal recovered, BigDecimal netTransfer) {
        static DailyDto of(DailyPoint d) {
            return new DailyDto(d.day(), d.date(), d.recovered(), d.netTransfer());
        }
    }

    @Schema(name = "SettlementBalance", description = "Demonstrativo aprovado com saldo a repassar ou a receber")
    record BalanceDto(int sequenceNumber, LocalDate periodStart, LocalDate periodEnd, BigDecimal netTransfer,
                      BigDecimal transferred, BigDecimal outstanding) {
        static BalanceDto of(StatementBalance b) {
            return new BalanceDto(b.sequenceNumber(), b.periodStart(), b.periodEnd(), b.netTransfer(), b.transferred(),
                    b.outstanding());
        }
    }

    @Schema(name = "SettlementPosition", description = "Repasse do período em aberto, estimado agora (ainda não é "
            + "demonstrativo: pode mudar até o fechamento)")
    record SettlementPositionDto(LocalDate periodStart, LocalDate asOf, LocalDate expectedPeriodEnd,
                                 SettlementTotalsDto totals,
                                 @Schema(allowableValues = {"AGENCY_TO_CREDITOR", "CREDITOR_TO_AGENCY", "NONE"})
                                 String direction, List<DailyDto> daily, List<DailyDto> previousDaily,
                                 List<BalanceDto> approvedWithBalance, BigDecimal outstandingToTransfer,
                                 BigDecimal outstandingToReceive, Instant calculatedAt) {
        static SettlementPositionDto of(PositionView p) {
            return new SettlementPositionDto(p.periodStart(), p.asOf(), p.expectedPeriodEnd(),
                    SettlementTotalsDto.of(p.totals()), p.direction(), p.daily().stream().map(DailyDto::of).toList(),
                    p.previousDaily().stream().map(DailyDto::of).toList(),
                    p.closedWithBalance().stream().map(BalanceDto::of).toList(), p.outstandingToTransfer(),
                    p.outstandingToReceive(), p.calculatedAt());
        }
    }

    @Schema(name = "SettlementStatement", description = "Demonstrativo enviado ao credor")
    record SettlementStatementDto(UUID id, int sequenceNumber, LocalDate periodStart, LocalDate periodEnd,
                                  @Schema(allowableValues = {"SENT", "ACKNOWLEDGED", "CONTESTED"}) String status,
                                  SettlementTotalsDto totals, Instant sentAt, Instant creditorResponseAt) {
        static SettlementStatementDto of(
                br.com.gestorcobranca.settlement.application.port.in.SettlementStatementUseCases.StatementView s) {
            return new SettlementStatementDto(s.id(), s.sequenceNumber(), s.periodStart(), s.periodEnd(), s.status(),
                    SettlementTotalsDto.of(s.totals()), s.sentAt(), s.creditorResponseAt());
        }
    }
}
