package br.com.gestorcobranca.ingestion.domain.service;

import br.com.gestorcobranca.ingestion.domain.model.TitleSituation;

/**
 * Tratamento da situação do título informada pelo credor (M02: títulos pagos, suspensos e
 * contestados). Regra conservadora: a carga nunca dá baixa financeira nem encerra um título —
 * isso exige o registro do pagamento ou o cancelamento explícito, com evidência — mas também nunca
 * deixa em cobrança um título que o credor diz estar pago, cancelado, suspenso ou contestado.
 * <table>
 *   <caption>Decisão por situação informada × status do título na base</caption>
 *   <tr><th>Situação</th><th>Título inexistente</th><th>Em cobrança (OPEN)</th><th>Suspenso</th><th>Encerrado</th></tr>
 *   <tr><td>ATIVO / vazio</td><td colspan="4">grava normalmente (o módulo debt protege títulos encerrados)</td></tr>
 *   <tr><td>SUSPENSO, CONTESTADO</td><td>cria e suspende</td><td>atualiza e suspende</td><td>atualiza</td><td>grava (debt avisa)</td></tr>
 *   <tr><td>PAGO, CANCELADO</td><td>não cria (aviso)</td><td>não altera; suspende para conciliação (aviso)</td><td>não altera (aviso)</td><td>não altera</td></tr>
 * </table>
 * Função pura: o status atual vem do chamador ({@code OPEN}, {@code SUSPENDED}, {@code SETTLED},
 * {@code CANCELLED}, {@code RETURNED} ou nulo quando o título não existe).
 */
public final class TitleSituationPolicy {

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_SUSPENDED = "SUSPENDED";

    private TitleSituationPolicy() {
    }

    /** Precisa conhecer o título existente antes de decidir (somente para pago/cancelado). */
    public static boolean requiresLookup(TitleSituation situation) {
        return situation != null && situation.closedAtCreditor();
    }

    /**
     * @param existingStatus status do título já existente, ou nulo se não existe
     */
    public static Decision decide(TitleSituation situation, String existingStatus) {
        TitleSituation effective = situation == null ? TitleSituation.ACTIVE : situation;
        if (!effective.closedAtCreditor()) {
            return new Decision(true, false, null, null);
        }
        String warning = effective == TitleSituation.PAID ? "row.title_reported_paid" : "row.title_reported_cancelled";
        if (existingStatus == null) {
            return new Decision(false, false, "row.title_closed_not_found",
                    "Título informado como %s não existe na base: não foi criado".formatted(label(effective)));
        }
        return switch (existingStatus) {
            case STATUS_OPEN -> new Decision(false, true, warning,
                    "Título informado como %s pelo credor: cobrança suspensa para conciliação".formatted(label(effective)));
            case STATUS_SUSPENDED -> new Decision(false, false, warning,
                    "Título informado como %s pelo credor: já estava suspenso, nada alterado".formatted(label(effective)));
            default -> new Decision(false, false, null, null);
        };
    }

    /** Após gravar: suspende se o credor pediu e o título está em cobrança. */
    public static boolean suspendAfterWrite(TitleSituation situation, String statusAfterWrite) {
        return situation != null && situation.holdsCollection() && STATUS_OPEN.equals(statusAfterWrite);
    }

    /** Código do aviso quando a carga suspende o título a pedido do credor. */
    public static String suspensionWarning(TitleSituation situation) {
        return situation == TitleSituation.DISPUTED ? "row.title_disputed_by_creditor" : "row.title_suspended_by_creditor";
    }

    /** Motivo da suspensão gravado no título (sem dados pessoais). */
    public static String suspensionReason(TitleSituation situation, String batchReference) {
        String cause = switch (situation) {
            case PAID -> "Credor informou pagamento; aguardando conciliação";
            case CANCELLED -> "Credor informou cancelamento; aguardando confirmação";
            case DISPUTED -> "Contestação informada pelo credor";
            default -> "Suspensão solicitada pelo credor";
        };
        return cause + " (carga " + batchReference + ")";
    }

    private static String label(TitleSituation situation) {
        return situation == TitleSituation.PAID ? "pago" : "cancelado";
    }

    /**
     * @param write           gravar devedor e título pelo {@code DebtIngestionPort}
     * @param suspendExisting suspender o título existente (sem gravar a linha)
     * @param warningCode     aviso para o relatório (nulo se nenhum)
     */
    public record Decision(boolean write, boolean suspendExisting, String warningCode, String warningMessage) {
    }
}
