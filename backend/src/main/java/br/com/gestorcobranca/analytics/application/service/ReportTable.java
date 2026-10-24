package br.com.gestorcobranca.analytics.application.service;

import br.com.gestorcobranca.analytics.application.port.in.AnalyticsQueries.BreakRate;
import br.com.gestorcobranca.analytics.application.port.in.AnalyticsQueries.ConversionReport;
import br.com.gestorcobranca.analytics.application.port.in.AnalyticsQueries.ConversionRow;
import br.com.gestorcobranca.analytics.application.port.in.AnalyticsQueries.CountRow;
import br.com.gestorcobranca.analytics.application.port.in.AnalyticsQueries.PortfolioReport;
import br.com.gestorcobranca.analytics.application.port.in.AnalyticsQueries.PortfolioRow;
import br.com.gestorcobranca.analytics.application.port.in.AnalyticsQueries.ProductivityReport;
import br.com.gestorcobranca.analytics.application.port.in.AnalyticsQueries.ProductivityRow;
import br.com.gestorcobranca.analytics.application.port.in.AnalyticsQueries.RecoveryReport;
import br.com.gestorcobranca.analytics.application.port.in.AnalyticsQueries.RecoveryRow;
import br.com.gestorcobranca.analytics.application.port.in.AnalyticsQueries.ReliabilityReport;
import br.com.gestorcobranca.analytics.application.port.in.AnalyticsQueries.ReportMeta;
import br.com.gestorcobranca.analytics.domain.model.Ratio;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Relatório em formato longo (uma linha por indicador), igual para todos os relatórios e pronto
 * para tabela dinâmica: seção, chave, rótulo, indicador, valor, numerador, denominador. Valores
 * numéricos como {@link BigDecimal}; frações (taxas) em escala 4, não percentuais.
 */
final class ReportTable {

    static final List<String> HEADER = List.of("secao", "chave", "rotulo", "indicador", "valor", "numerador",
            "denominador", "observacao");

    record Line(String section, String key, String label, String indicator, BigDecimal value, BigDecimal numerator,
                BigDecimal denominator, String note) {
    }

    private final List<Line> lines = new ArrayList<>();

    List<Line> lines() {
        return lines;
    }

    private ReportTable value(String section, String key, String label, String indicator, BigDecimal value) {
        lines.add(new Line(section, key, label, indicator, value, null, null, null));
        return this;
    }

    private ReportTable value(String section, String key, String label, String indicator, long value) {
        return value(section, key, label, indicator, BigDecimal.valueOf(value));
    }

    private ReportTable ratio(String section, String key, String label, String indicator, Ratio ratio) {
        if (ratio == null) {
            return this;
        }
        lines.add(new Line(section, key, label, indicator, ratio.value(), ratio.numerator(), ratio.denominator(),
                ratio.unavailableReason()));
        return this;
    }

    private void meta(ReportMeta meta) {
        lines.add(new Line("parametros", "periodo", meta.from() + " a " + meta.to(), "dados_ate", null, null, null,
                meta.dataUntil() == null ? "ainda não atualizado" : meta.dataUntil().toString()));
        if (meta.peopleRestricted()) {
            lines.add(new Line("parametros", "escopo", "Pessoas", "restrito_ao_escopo", null, null, null,
                    "dados por pessoa limitados ao escopo do usuário"));
        }
        meta.notApplicableFilters().forEach(f -> lines.add(new Line("parametros", "filtro", f, "nao_aplicavel", null,
                null, null, null)));
    }

    static ReportTable of(PortfolioReport r) {
        ReportTable t = new ReportTable();
        t.meta(r.meta());
        var x = r.totals();
        String s = "totais";
        t.value(s, "ALL", "Total", "valor_carteira", x.portfolioAmount())
                .value(s, "ALL", "Total", "titulos_em_aberto", x.openTitles())
                .value(s, "ALL", "Total", "saldo_vencido", x.overdueAmount())
                .ratio(s, "ALL", "Total", "participacao_vencido", x.overdueShare())
                .value(s, "ALL", "Total", "saldo_elegivel", x.eligibleBalance())
                .ratio(s, "ALL", "Total", "participacao_elegivel", x.eligibleShare())
                .value(s, "ALL", "Total", "casos_abertos", x.openCases())
                .value(s, "ALL", "Total", "casos_elegiveis", x.eligibleCases())
                .value(s, "ALL", "Total", "fila_pendente_casos", x.pendingQueueCases())
                .value(s, "ALL", "Total", "fila_pendente_saldo", x.pendingQueueBalance())
                .ratio(s, "ALL", "Total", "participacao_fila_pendente", x.pendingQueueShare());
        t.portfolioRows("por_credor", r.byCreditor());
        t.portfolioRows("por_carteira", r.byPortfolio());
        t.portfolioRows("por_faixa_atraso", r.byDelayBucket());
        r.casesByStatus().forEach(c -> t.value("casos_por_estado", c.status(), c.status(), "casos", c.cases())
                .value("casos_por_estado", c.status(), c.status(), "saldo_em_aberto", c.openBalance())
                .value("casos_por_estado", c.status(), c.status(), "saldo_elegivel", c.eligibleBalance()));
        r.pendingQueue().forEach(q -> t.value("fila_pendente", q.queueId(), q.label(), "casos", q.cases())
                .value("fila_pendente", q.queueId(), q.label(), "saldo_elegivel", q.eligibleBalance()));
        return t;
    }

    private void portfolioRows(String section, List<PortfolioRow> rows) {
        for (PortfolioRow p : rows) {
            value(section, p.key(), p.label(), "valor_carteira", p.portfolioAmount())
                    .value(section, p.key(), p.label(), "saldo_vencido", p.overdueAmount())
                    .ratio(section, p.key(), p.label(), "participacao_vencido", p.overdueShare())
                    .value(section, p.key(), p.label(), "saldo_elegivel", p.eligibleBalance())
                    .value(section, p.key(), p.label(), "casos_abertos", p.openCases());
        }
    }

    static ReportTable of(RecoveryReport r) {
        ReportTable t = new ReportTable();
        t.meta(r.meta());
        var x = r.totals();
        String s = "totais";
        t.value(s, "ALL", "Total", "recebido_bruto", x.recoveredGross())
                .value(s, "ALL", "Total", "estornado", x.reversed())
                .value(s, "ALL", "Total", "recuperado_liquido", x.recoveredNet())
                .value(s, "ALL", "Total", "recebimentos", x.receipts())
                .ratio(s, "ALL", "Total", "taxa_recuperacao", x.recoveryRate())
                .ratio(s, "ALL", "Total", "participacao_estornos", x.reversalShare());
        var ttr = r.timeToReceipt();
        t.value("tempo_ate_recebimento", "ALL", "Total", "acordos", ttr.agreements())
                .value("tempo_ate_recebimento", "ALL", "Total", "media_dias", ttr.averageDays())
                .value("tempo_ate_recebimento", "ALL", "Total", "mediana_dias", ttr.medianDays())
                .value("tempo_ate_recebimento", "ALL", "Total", "p90_dias", ttr.p90Days());
        t.ratio("custo", "ALL", "Total", "comissao_por_real_recuperado", r.cost().commissionPerReal());
        String g = "por_" + r.meta().groupBy().toLowerCase();
        for (RecoveryRow row : r.rows()) {
            t.value(g, row.key(), row.label(), "recebido_bruto", row.recoveredGross())
                    .value(g, row.key(), row.label(), "estornado", row.reversed())
                    .value(g, row.key(), row.label(), "recuperado_liquido", row.recoveredNet())
                    .value(g, row.key(), row.label(), "recebimentos", row.receipts())
                    .ratio(g, row.key(), row.label(), "taxa_recuperacao", row.recoveryRate());
        }
        return t;
    }

    static ReportTable of(ConversionReport r) {
        ReportTable t = new ReportTable();
        t.meta(r.meta());
        var f = r.funnel();
        String s = "funil";
        t.value(s, "ALL", "Total", "contatos_efetivos", f.effectiveContacts())
                .value(s, "ALL", "Total", "propostas", f.proposals())
                .value(s, "ALL", "Total", "acordos", f.agreements())
                .value(s, "ALL", "Total", "primeira_parcela_paga", f.firstInstallmentsPaid())
                .ratio(s, "ALL", "Total", "contato_para_proposta", f.contactToProposal())
                .ratio(s, "ALL", "Total", "proposta_para_acordo", f.proposalToAgreement())
                .ratio(s, "ALL", "Total", "acordo_para_primeira_parcela", f.agreementToFirstInstallment())
                .ratio(s, "ALL", "Total", "contato_para_acordo", f.contactToAgreement());
        var q = r.quality();
        String sq = "safra";
        t.value(sq, "ALL", "Total", "valor_acordado", q.agreedAmount())
                .value(sq, "ALL", "Total", "valor_pago", q.paidAmount())
                .ratio(sq, "ALL", "Total", "pago_sobre_acordado", q.paidShare())
                .value(sq, "ALL", "Total", "parcelas_pagas_no_periodo", q.installmentsPaid())
                .value(sq, "ALL", "Total", "valor_parcelas_pagas_no_periodo", q.installmentsPaidAmount())
                .ratio(sq, "ALL", "Total", "taxa_renegociacao", q.renegotiationRate());
        for (BreakRate b : q.breaks()) {
            t.ratio(sq, "ALL", "Total", "quebra_" + b.windowDays() + "_dias", b.rate());
        }
        String g = "por_" + r.meta().groupBy().toLowerCase();
        for (ConversionRow row : r.rows()) {
            if (row.effectiveContacts() != null) {
                t.value(g, row.key(), row.label(), "contatos_efetivos", row.effectiveContacts());
            }
            if (row.proposals() != null) {
                t.value(g, row.key(), row.label(), "propostas", row.proposals());
            }
            t.value(g, row.key(), row.label(), "acordos", row.agreements())
                    .value(g, row.key(), row.label(), "valor_acordado", row.agreedAmount())
                    .value(g, row.key(), row.label(), "valor_pago", row.paidAmount())
                    .ratio(g, row.key(), row.label(), "pago_sobre_acordado", row.paidShare())
                    .ratio(g, row.key(), row.label(), "taxa_primeira_parcela", row.firstInstallmentRate())
                    .ratio(g, row.key(), row.label(), "contato_para_acordo", row.contactToAgreement());
        }
        return t;
    }

    static ReportTable of(ProductivityReport r) {
        ReportTable t = new ReportTable();
        t.meta(r.meta());
        t.productivity("totais", r.total());
        String g = "por_" + r.meta().groupBy().toLowerCase();
        r.rows().forEach(row -> t.productivity(g, row));
        return t;
    }

    private void productivity(String s, ProductivityRow p) {
        value(s, p.key(), p.label(), "casos_trabalhados", p.workedCases())
                .value(s, p.key(), p.label(), "tentativas", p.attempts())
                .value(s, p.key(), p.label(), "contatos_efetivos", p.effectiveContacts())
                .ratio(s, p.key(), p.label(), "taxa_contato_efetivo", p.contactRate())
                .value(s, p.key(), p.label(), "chamadas_atendidas", p.answeredCalls())
                .ratio(s, p.key(), p.label(), "tempo_medio_atendimento_segundos", p.averageHandlingSeconds())
                .value(s, p.key(), p.label(), "horas_logadas", p.loggedHours())
                .value(s, p.key(), p.label(), "acordos", p.agreements())
                .ratio(s, p.key(), p.label(), "acordos_por_hora", p.agreementsPerLoggedHour())
                .value(s, p.key(), p.label(), "recuperado_liquido", p.recoveredNet());
    }

    static ReportTable of(ReliabilityReport r) {
        ReportTable t = new ReportTable();
        t.meta(r.meta());
        var i = r.integrations();
        t.value("integracoes", "ALL", "Instruções de pagamento", "geradas", i.paymentInstructions())
                .ratio("integracoes", "ALL", "Instruções de pagamento", "taxa_falha", i.instructionFailureRate())
                .value("integracoes", "ALL", "Chamadas", "total", i.calls())
                .ratio("integracoes", "ALL", "Chamadas", "taxa_falha", i.callFailureRate())
                .value("integracoes", "ALL", "Extratos bancários", "importados", i.statementImports())
                .value("integracoes", "ALL", "Extratos bancários", "falhas", i.statementImportsFailed());
        counts(t, "falhas_instrucao", i.instructionFailuresByCode());
        var m = r.messages();
        t.value("mensagens", "ALL", "Total", "enviadas", m.outbound())
                .ratio("mensagens", "ALL", "Total", "taxa_falha", m.failureRate())
                .value("mensagens", "ALL", "Total", "sla_primeira_resposta_estourado", m.slaBreaches())
                .ratio("duplicidades", "ALL", "Total", "linhas_extrato_duplicadas", m.duplicateLineRate())
                .value("duplicidades", "ALL", "Total", "pagamentos_duplicados", m.duplicatePaymentDivergences());
        counts(t, "falhas_mensagem", m.failuresByCode());
        var c = r.reconciliation();
        t.value("conciliacao", "ALL", "Total", "divergencias_abertas_no_periodo", c.opened())
                .value("conciliacao", "ALL", "Total", "divergencias_em_aberto", c.openNow())
                .value("conciliacao", "ALL", "Total", "valor_em_aberto", c.openNowAmount())
                .value("conciliacao", "ALL", "Total", "resolvidas_no_periodo", c.resolved())
                .value("conciliacao", "ALL", "Total", "mediana_horas_resolucao", c.medianResolutionHours());
        counts(t, "divergencias_por_tipo", c.openedByType());
        if (r.jobs() != null) {
            t.value("jobs", "ALL", "Total", "dead_agora", r.jobs().deadNow())
                    .ratio("jobs", "ALL", "Total", "taxa_dead_no_periodo", r.jobs().deadRate());
            counts(t, "jobs_dead_por_tipo", r.jobs().deadByType());
        }
        var im = r.imports();
        t.value("importacoes", "ALL", "Total", "lotes", im.batches())
                .ratio("importacoes", "ALL", "Total", "taxa_falha", im.failureRate())
                .value("importacoes", "ALL", "Total", "mediana_segundos", im.medianSeconds())
                .value("importacoes", "ALL", "Total", "p95_segundos", im.p95Seconds())
                .ratio("importacoes", "ALL", "Total", "taxa_linhas_com_erro", im.rowErrorRate());
        return t;
    }

    private static void counts(ReportTable t, String section, List<CountRow> rows) {
        rows.forEach(c -> t.value(section, c.key(), c.label(), "quantidade", c.count()));
    }
}
