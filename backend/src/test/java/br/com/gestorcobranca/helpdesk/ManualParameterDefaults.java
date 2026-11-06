package br.com.gestorcobranca.helpdesk;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Padrões dos parâmetros citados no manual ({@code application.yml}), na chave do contrato com o frontend
 * ({@code manual-parameters.ts}). {@code ManualParametersIT} confere que a aplicação publica exatamente estes;
 * os testes da base do chat os usam para resolver os marcadores.
 */
public final class ManualParameterDefaults {

    public static final Map<String, BigDecimal> DEFAULTS = Map.ofEntries(
            Map.entry("debt.cumulativeWindowDays", new BigDecimal("30")),
            Map.entry("debt.proposalValidityDays", new BigDecimal("15")),
            Map.entry("negotiation.breakSuspensionMaxDays", new BigDecimal("7")),
            Map.entry("negotiation.breakSuspensionGraceDays", new BigDecimal("3")),
            Map.entry("payment.rejectedReviewWindowDays", new BigDecimal("30")),
            Map.entry("compliance.dataSubjectRequestDueDays", new BigDecimal("15")),
            Map.entry("pendingwork.dueSoonDays", new BigDecimal("3")),
            Map.entry("telephony.presenceTimeoutSeconds", new BigDecimal("90")),
            Map.entry("ingestion.pdfMaxFileSizeMb", new BigDecimal("20")),
            Map.entry("scoring.outcomeDays", new BigDecimal("30")),
            Map.entry("scoring.outcomeGraceDays", new BigDecimal("5")),
            Map.entry("scoring.minAuc", new BigDecimal("0.60")),
            Map.entry("scoring.psiWarning", new BigDecimal("0.10")),
            Map.entry("scoring.psiAlert", new BigDecimal("0.25")),
            Map.entry("settlement.adjustmentDualApprovalThreshold", new BigDecimal("1000.00")));

    private ManualParameterDefaults() {
    }
}
