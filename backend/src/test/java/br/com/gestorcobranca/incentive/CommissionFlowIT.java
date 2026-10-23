package br.com.gestorcobranca.incentive;

import br.com.gestorcobranca.identity.domain.model.DefaultRoles;
import br.com.gestorcobranca.incentive.application.port.in.PeriodUseCases;
import br.com.gestorcobranca.incentive.application.port.in.StatementExportUseCases;
import br.com.gestorcobranca.incentive.application.port.in.StatementExportUseCases.ExportFile;
import br.com.gestorcobranca.incentive.application.port.in.StatementUseCases;
import br.com.gestorcobranca.incentive.application.port.in.StatementUseCases.DisputeCommand;
import br.com.gestorcobranca.incentive.application.port.in.StatementUseCases.StatementDetail;
import br.com.gestorcobranca.incentive.application.port.in.StatementUseCases.StatementSummary;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.security.ScopeAccessDeniedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fluxo completo: atribuição → pagamento → carência em dias úteis (com feriado) → estorno na
 * carência → fechamento → contestação → ajuste com dupla aprovação → aprovação segregada →
 * exportação → estorno depois do fechamento; escopo próprio/equipe e log de acesso.
 */
class CommissionFlowIT extends IncentiveTestSupport {

    private static final YearMonth AUGUST = YearMonth.of(2026, 8);

    @Autowired
    PeriodUseCases periods;
    @Autowired
    StatementUseCases statements;
    @Autowired
    StatementExportUseCases exports;

    UUID agreement;
    UUID payment1;
    UUID payment2;

    @BeforeEach
    void setUp() {
        provisionTenant("flow");
        // feriado na segunda 10/08/2026: carência de 5 dias úteis de quarta 05/08 vai a quinta 13/08
        system(() -> holidays.add(LocalDate.of(2026, 8, 10), "Feriado municipal"));
        agreement = Ids.newId();
        payment1 = Ids.newId();
        payment2 = Ids.newId();
        // acordo humano do operador X, título com 400 dias de atraso (faixa > 360: multiplicador 1,8)
        publishAndAwait(attribution(agreement, Ids.newId(), "HUMAN", List.of(closer(operatorX)), "10000.00", 400, null,
                at(2026, 8, 3)), "incentive.attribution-recorded");
    }

    @Test
    void fullCycleFromAttributionToExportWithSegregationAndReversals() throws IOException {
        publishAndAwait(payment(payment1, agreement, "4000.00", at(2026, 8, 5)), "incentive.payment-confirmed");
        publishAndAwait(payment(payment2, agreement, "1000.00", at(2026, 8, 6)), "incentive.payment-confirmed");
        // estorno dentro da carência: compensação acompanha o crédito e o anula no mesmo período
        publishAndAwait(reversal(payment2, agreement, "1000.00", at(2026, 8, 7)), "incentive.payment-reversed");

        assertThat(count("select count(*) from incentive.commission_ledger_entry where kind = 'ACCRUAL'")).isEqualTo(4);
        assertThat(count("select count(*) from incentive.commission_ledger_entry where kind = 'REVERSAL' "
                + "and status = 'PENDING'")).isEqualTo(2);
        assertThat(system(() -> jdbcTemplate.queryForObject("""
                select clears_on from incentive.commission_ledger_entry
                where payment_id = ? and beneficiary_type = 'USER'""", LocalDate.class, payment1)))
                .isEqualTo(LocalDate.of(2026, 8, 13));

        // fechamento manual pelo administrador (carência do último dia já encerrada)
        PeriodUseCases.PeriodView closed = as(admin, DefaultRoles.ADMIN, () -> periods.close(AUGUST));
        assertThat(closed.status()).isEqualTo("CLOSED");
        assertThat(count("select count(*) from incentive.commission_ledger_entry where status = 'PENDING'")).isZero();

        StatementSummary x = statementOf(operatorX);
        // 4.000 líquidos (5.000 − 1.000) × 1,5% × 1,8 = 108,00
        assertThat(x.creditedRecovery()).isEqualByComparingTo("4000.00");
        assertThat(x.calculatedAmount()).isEqualByComparingTo("108.00");
        assertThat(x.status()).isEqualTo("CALCULATED");
        // supervisor: pool de 0,3% sobre o recuperado da equipe = 12,00; o operador Y não recebe extrato
        assertThat(statementOf(supervisor).calculatedAmount()).isEqualByComparingTo("12.00");
        assertThat(count("select count(*) from incentive.commission_statement where beneficiary_id = ?", operatorY))
                .isZero();

        // escopo: Y (próprio) não vê o extrato de X; S (equipe) vê e a consulta gera log de acesso
        assertThatThrownBy(() -> as(operatorY, DefaultRoles.OPERADOR, () -> statements.get(x.id())))
                .isInstanceOf(ResourceNotFoundException.class);
        long accessBefore = accessLogs("COMPENSATION_DATA_VIEWED");
        StatementDetail seenBySupervisor = as(supervisor, DefaultRoles.SUPERVISOR, () -> statements.get(x.id()));
        assertThat(seenBySupervisor.lines()).isNotEmpty();
        assertThat(accessLogs("COMPENSATION_DATA_VIEWED")).isEqualTo(accessBefore + 1);
        as(operatorX, DefaultRoles.OPERADOR, () -> statements.get(x.id()));
        assertThat(accessLogs("COMPENSATION_DATA_VIEWED")).isEqualTo(accessBefore + 1);

        // contestação item a item pelo próprio negociador
        assertThatThrownBy(() -> as(operatorY, DefaultRoles.OPERADOR, () -> statements.openDispute(x.id(),
                new DisputeCommand(1, null, null, "Linha com valor incorreto")))).isInstanceOf(ResourceNotFoundException.class);
        StatementUseCases.DisputeView dispute = as(operatorX, DefaultRoles.OPERADOR, () -> statements.openDispute(x.id(),
                new DisputeCommand(1, null, null, "Faltou o acordo renegociado no início do mês")));
        assertThat(statementOf(operatorX).status()).isEqualTo("DISPUTED");

        // aceita com ajuste de R$ 600 (acima do limite de R$ 500): aguarda segunda aprovação
        as(manager, DefaultRoles.GESTOR, () -> statements.resolveDispute(x.id(), dispute.id(), true,
                "Acordo confirmado no sistema de origem", new BigDecimal("600.00")));
        StatementDetail afterDispute = as(manager, DefaultRoles.GESTOR, () -> statements.get(x.id()));
        UUID adjustmentId = afterDispute.adjustments().getFirst().id();
        assertThat(afterDispute.adjustments().getFirst().status()).isEqualTo("PENDING_SECOND_APPROVAL");
        assertThat(afterDispute.summary().payableAmount()).isEqualByComparingTo("108.00");
        assertThatThrownBy(() -> as(finance, DefaultRoles.FINANCEIRO, () -> statements.approve(x.id())))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> as(manager, DefaultRoles.GESTOR, () -> statements.decideAdjustment(x.id(), adjustmentId,
                true, null))).isInstanceOf(BusinessRuleException.class);
        as(manager2, DefaultRoles.GESTOR, () -> statements.decideAdjustment(x.id(), adjustmentId, true, null));
        StatementSummary adjusted = statementOf(operatorX);
        assertThat(adjusted.status()).isEqualTo("ADJUSTED");
        assertThat(adjusted.payableAmount()).isEqualByComparingTo("708.00");

        // aprovação exige a permissão financeira; quem fechou o período não aprova (mesmo sendo administrador)
        assertThatThrownBy(() -> as(manager2, DefaultRoles.GESTOR, () -> statements.approve(x.id())))
                .isInstanceOf(ScopeAccessDeniedException.class);
        assertThatThrownBy(() -> as(admin, DefaultRoles.ADMIN, () -> statements.approve(x.id())))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("fechou o período");
        assertThatThrownBy(() -> as(operatorX, DefaultRoles.OPERADOR, () -> statements.approve(x.id())))
                .isInstanceOf(ScopeAccessDeniedException.class);
        StatementDetail approved = as(finance, DefaultRoles.FINANCEIRO, () -> statements.approve(x.id()));
        assertThat(approved.summary().status()).isEqualTo("APPROVED");
        assertThatThrownBy(() -> as(manager, DefaultRoles.GESTOR, () -> statements.adjust(x.id(), new BigDecimal("10"),
                "Correção posterior à aprovação"))).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> system(() -> jdbcTemplate.update(
                "update incentive.commission_statement set payable_amount = 1 where id = ?", x.id())))
                .isInstanceOf(DataAccessException.class);
        as(finance, DefaultRoles.FINANCEIRO, () -> statements.approve(statementOf(supervisor).id()));

        // exportação para a folha
        ExportFile file = as(finance, DefaultRoles.FINANCEIRO, () -> exports.export(AUGUST, "CSV"));
        String csv = read(file.content());
        assertThat(csv).contains(operatorX.toString()).contains("708.00").contains("12.00");
        assertThat(statementOf(operatorX).status()).isEqualTo("EXPORTED");
        assertThat(accessLogs("DATA_EXPORTED")).isEqualTo(1);
        UUID exportId = as(finance, DefaultRoles.FINANCEIRO, () -> exports.list(AUGUST)).getFirst().id();
        assertThat(read(as(finance, DefaultRoles.FINANCEIRO, () -> exports.download(exportId)).content())).isEqualTo(csv);
        ExportFile xlsx = as(finance, DefaultRoles.FINANCEIRO, () -> exports.export(AUGUST, "XLSX"));
        assertThat(xlsx.size()).isPositive();

        // estorno depois do fechamento sem estorno de comissão no plano: registrado e dispensado (WAIVED)
        publishAndAwait(reversal(payment1, agreement, "4000.00", at(2026, 9, 10)), "incentive.payment-reversed");
        assertThat(count("select count(*) from incentive.commission_ledger_entry where kind = 'REVERSAL' "
                + "and status = 'WAIVED' and payment_id = ?", payment1)).isEqualTo(2);
        assertThat(statementOf(operatorX).payableAmount()).isEqualByComparingTo("708.00");
        // o indicador operacional reflete o estorno (recuperado líquido)
        assertThat(decimal("select sum(accrued_amount) - sum(reversed_amount) from incentive.performance_fact "
                + "where user_id = ?", operatorX)).isEqualByComparingTo("0.00");

        // eventos de negócio do ciclo, sem valor individual de comissão
        for (String type : List.of("attribution.recorded", "commission.forecast", "commission.entry_posted",
                "commission.entry_cleared", "commission.entry_reversed", "commission.period_closed",
                "commission.statement_disputed", "commission.statement_adjusted", "commission.statement_approved",
                "commission.statement_exported")) {
            assertThat(count("select count(*) from shared.business_event where event_type = ?", type))
                    .as(type).isPositive();
        }
        assertThat(count("""
                select count(*) from shared.business_event
                where event_type in ('commission.statement_approved', 'commission.statement_adjusted', 'commission.forecast')
                  and amount is not null""")).isZero();
    }

    @Test
    void reversalAfterClearingInAnOpenPeriodNetsOutInTheSamePeriod() {
        UUID september = Ids.newId();
        publishAndAwait(payment(september, agreement, "2000.00", at(2026, 9, 1)), "incentive.payment-confirmed");
        // o mês corrente não pode ser fechado
        assertThatThrownBy(() -> as(manager, DefaultRoles.GESTOR, () -> periods.close(YearMonth.from(LocalDate.now(ZONE)))))
                .isInstanceOf(BusinessRuleException.class);
        // fechar agosto (sem lançamentos) libera as carências vencidas: o crédito de 01/09 vai para setembro (aberto)
        assertThat(as(manager, DefaultRoles.GESTOR, () -> periods.close(AUGUST)).statements()).isZero();
        assertThat(count("select count(*) from incentive.commission_ledger_entry where status = 'CLEARED' "
                + "and period_start = date '2026-09-01'")).isEqualTo(2);
        // estorno parcial (25%) depois da liberação, com o período ainda aberto: compensação liberada no mesmo período
        publishAndAwait(reversal(september, agreement, "500.00", at(2026, 9, 20)), "incentive.payment-reversed");
        assertThat(count("select count(*) from incentive.commission_ledger_entry where kind = 'REVERSAL' "
                + "and status = 'CLEARED' and period_start = date '2026-09-01'")).isEqualTo(2);
        PeriodUseCases.PeriodView closed = as(manager, DefaultRoles.GESTOR, () -> periods.close(YearMonth.of(2026, 9)));
        assertThat(closed.statements()).isEqualTo(2);
        // pool do supervisor: (2.000 − 500) × 0,3% = 4,50
        assertThat(statementOf(supervisor, YearMonth.of(2026, 9)).calculatedAmount()).isEqualByComparingTo("4.50");
        StatementSummary x = statementOf(operatorX, YearMonth.of(2026, 9));
        assertThat(x.creditedRecovery()).isEqualByComparingTo("1500.00");
        // 1.500 × 1,5% × 1,8 = 40,50
        assertThat(x.calculatedAmount()).isEqualByComparingTo("40.50");
        assertThatThrownBy(() -> as(manager, DefaultRoles.GESTOR, () -> periods.close(YearMonth.of(2026, 9))))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void ledgerIsAppendOnlyInTheDatabase() {
        publishAndAwait(payment(payment1, agreement, "4000.00", at(2026, 8, 5)), "incentive.payment-confirmed");
        assertThatThrownBy(() -> system(() -> jdbcTemplate.update(
                "update incentive.commission_ledger_entry set credited_amount = 1")))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> system(() -> jdbcTemplate.update("delete from incentive.commission_ledger_entry")))
                .isInstanceOf(DataAccessException.class);
        // a reentrega do mesmo pagamento (novo eventId) não duplica o crédito
        publishAndAwait(payment(payment1, agreement, "4000.00", at(2026, 8, 5)), "incentive.payment-confirmed");
        assertThat(count("select count(*) from incentive.commission_ledger_entry where payment_id = ?", payment1))
                .isEqualTo(2);
    }

    @Test
    void ownLedgerAndPreviewAreVisibleOnlyInScope() {
        publishAndAwait(payment(payment1, agreement, "4000.00", at(2026, 8, 5)), "incentive.payment-confirmed");
        assertThat(as(operatorX, DefaultRoles.OPERADOR, () -> statements.ledger(null, null, PageQuery.of(0, 50)))
                .totalElements()).isEqualTo(1);
        assertThatThrownBy(() -> as(operatorY, DefaultRoles.OPERADOR, () -> statements.ledger(operatorX, null,
                PageQuery.of(0, 50)))).isInstanceOf(ScopeAccessDeniedException.class);
        assertThat(as(supervisor, DefaultRoles.SUPERVISOR, () -> statements.ledger(operatorX, null, PageQuery.of(0, 50)))
                .totalElements()).isEqualTo(1);
        assertThat(as(operatorX, DefaultRoles.OPERADOR, () -> statements.preview(null)).planCode())
                .isEqualTo("PADRAO_MERCADO");
    }

    private StatementSummary statementOf(UUID userId) {
        return statementOf(userId, AUGUST);
    }

    private StatementSummary statementOf(UUID userId, YearMonth period) {
        return as(manager, DefaultRoles.GESTOR, () -> statements.list(period, userId, null, PageQuery.of(0, 10)))
                .content().getFirst();
    }

    private long accessLogs(String type) {
        return count("select count(*) from shared.access_log where event_type = ?", type);
    }

    private static String read(InputStream in) throws IOException {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
