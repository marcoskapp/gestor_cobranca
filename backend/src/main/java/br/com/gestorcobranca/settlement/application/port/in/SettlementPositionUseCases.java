package br.com.gestorcobranca.settlement.application.port.in;

import br.com.gestorcobranca.settlement.application.port.in.SettlementStatementUseCases.StatementView;
import br.com.gestorcobranca.settlement.application.port.in.SettlementStatementUseCases.TotalsView;
import br.com.gestorcobranca.settlement.application.port.in.TransferUseCases.StatementBalance;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Posição do repasse ao credor a qualquer momento, sem esperar o fechamento: o período em aberto (do fim do último
 * demonstrativo aprovado até hoje) calculado com <strong>o mesmo cálculo do demonstrativo</strong>, em modo simulação
 * — nada é gravado, vinculado ou bloqueado. Consulta: {@code SETTLEMENT_VIEW}. Escopo por credor.
 */
public interface SettlementPositionUseCases {

    PositionView position(UUID creditorId);

    /**
     * Demonstrativos já enviados ao credor ({@code SENT}, {@code ACKNOWLEDGED}, {@code CONTESTED}), do mais recente
     * para o mais antigo — nunca rascunhos ou em revisão (exposição ao próprio credor pela API pública).
     */
    List<StatementView> sentToCreditor(UUID creditorId);

    /**
     * @param periodStart        início do período em aberto (dia seguinte ao último demonstrativo fechado; sem
     *                           demonstrativo, início do ciclo corrente do contrato)
     * @param asOf               fim da simulação (hoje, no fuso de negócio)
     * @param expectedPeriodEnd  fechamento previsto pelo dia de repasse do contrato
     * @param openStatementId    demonstrativo em aberto (rascunho/calculado/revisado), se houver — os itens e ajustes
     *                           dele entram na simulação como se fosse recalculado agora
     * @param totals             totais como no demonstrativo; {@code netTransfer} positivo = a assessoria repassa,
     *                           negativo = o credor deve; pendências de conciliação e de regra ficam fora do líquido
     * @param direction          AGENCY_TO_CREDITOR, CREDITOR_TO_AGENCY ou NONE (pelo sinal do líquido)
     * @param daily              acumulado diário do período (dia 1 = início)
     * @param previousDaily      acumulado diário do período anterior (último demonstrativo fechado), mesmo dia
     * @param closedWithBalance  demonstrativos aprovados com saldo a repassar ou a receber
     * @param calculatedAt       instante do cálculo (pode vir do cache curto, ver {@code cached})
     */
    record PositionView(UUID creditorId, LocalDate periodStart, LocalDate asOf, LocalDate expectedPeriodEnd,
                        Integer settlementDay, UUID openStatementId, String openStatementStatus, TotalsView totals,
                        String direction, List<DailyPoint> daily, PreviousPeriod previous, List<DailyPoint> previousDaily,
                        List<StatementBalance> closedWithBalance, BigDecimal outstandingToTransfer,
                        BigDecimal outstandingToReceive, Instant calculatedAt, boolean cached, long calculationMillis) {
    }

    /** Valores acumulados até o fim do dia {@code day} do período. */
    record DailyPoint(int day, LocalDate date, BigDecimal recovered, BigDecimal netTransfer) {
    }

    record PreviousPeriod(UUID statementId, int sequenceNumber, LocalDate periodStart, LocalDate periodEnd,
                          String status, BigDecimal recovered, BigDecimal netTransfer) {
    }
}
