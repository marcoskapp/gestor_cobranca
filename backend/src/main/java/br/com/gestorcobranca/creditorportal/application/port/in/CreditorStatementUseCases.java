package br.com.gestorcobranca.creditorportal.application.port.in;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Prestação de contas no portal do credor: lista, ficha, arquivos, posição do período em aberto e as respostas (aceite
 * com o código do autenticador, contestação com motivo). O credor vem sempre da sessão.
 */
public interface CreditorStatementUseCases {

    List<StatementItem> statements();

    StatementDetail statement(UUID statementId);

    OpenPeriod openPeriod();

    FileContent download(UUID statementId, UUID fileId);

    /** Confere o código do autenticador e aceita, na mesma transação (sem sessão elevada reutilizável). */
    StatementDetail acknowledge(UUID statementId, AcknowledgeCommand command);

    StatementDetail contest(UUID statementId, ContestCommand command);

    /**
     * @param code       código de 6 dígitos do autenticador
     * @param fileSha256 SHA-256 dos arquivos mostrados à pessoa (precisam ser os atuais)
     */
    record AcknowledgeCommand(String code, String note, List<String> fileSha256, String clientIp) {
    }

    record ContestCommand(String reason, String clientIp) {
    }

    /**
     * @param status          SENT (aguarda a resposta), ACKNOWLEDGED (aceito) ou CONTESTED (contestado)
     * @param direction       AGENCY_TO_CREDITOR, CREDITOR_TO_AGENCY ou NONE
     * @param responseDueOn   prazo para aceitar ou contestar (só lembrete: vencido, continua SENT)
     * @param responseOverdue aguarda a resposta e o prazo já passou
     * @param transferred     já repassado (ou recebido) do líquido
     * @param outstanding     quanto falta repassar (ou receber)
     */
    record StatementItem(UUID id, int sequenceNumber, LocalDate periodStart, LocalDate periodEnd, String status,
                         BigDecimal recovered, BigDecimal netTransfer, String direction, Instant sentAt,
                         Instant respondedAt, LocalDate responseDueOn, boolean responseOverdue, BigDecimal transferred,
                         BigDecimal outstanding) {
    }

    record Totals(BigDecimal receivedByAgency, BigDecimal receivedByCreditor, BigDecimal reversed, BigDecimal recovered,
                  BigDecimal agencyHonorarium, BigDecimal commission, BigDecimal fixedFees, BigDecimal expenses,
                  BigDecimal adjustments, BigDecimal netTransfer) {
    }

    /** @param format CSV, XLSX ou PDF */
    record FileItem(UUID id, String format, String fileName, long size, String sha256, Instant createdAt) {
    }

    record StatementDetail(StatementItem statement, Totals totals, List<FileItem> files, BigDecimal transferred,
                           BigDecimal outstanding, String responseNote, boolean respondedInPortal) {
    }

    /**
     * @param daily         acumulado por dia do período em aberto (dia 1 = início)
     * @param previousDaily acumulado por dia do período anterior, para comparar no mesmo dia
     */
    record OpenPeriod(LocalDate periodStart, LocalDate asOf, LocalDate expectedPeriodEnd, BigDecimal recovered,
                      BigDecimal netTransfer, String direction, Instant calculatedAt, List<DailyPoint> daily,
                      List<DailyPoint> previousDaily) {
    }

    record DailyPoint(int day, LocalDate date, BigDecimal recovered, BigDecimal netTransfer) {
    }

    record FileContent(String fileName, String contentType, long size, String sha256, InputStream content) {
    }
}
