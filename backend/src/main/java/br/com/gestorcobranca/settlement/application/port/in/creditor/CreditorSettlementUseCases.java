package br.com.gestorcobranca.settlement.application.port.in.creditor;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Prestação de contas vista e respondida pela pessoa do credor no portal do credor (ADR 0011, fatia 4). Só o ator
 * {@code CREDITOR}, com o credor vindo do ator (nunca do cliente); só demonstrativos já enviados ({@code SENT},
 * {@code ACKNOWLEDGED}, {@code CONTESTED}) — rascunho, cálculo e aprovação nunca aparecem; nenhum identificador de
 * pessoa da assessoria nem a memória de cálculo interna.
 */
public interface CreditorSettlementUseCases {

    /** Demonstrativos enviados ao credor, do mais recente ao mais antigo ({@code CREDITOR_PORTAL_OVERVIEW}). */
    List<CreditorStatementSummary> statements();

    CreditorStatementDetail statement(UUID statementId);

    /** Posição do período em aberto (estimativa, sujeita ao fechamento). */
    CreditorOpenPeriod openPeriod();

    /** Arquivo do demonstrativo (CSV, XLSX ou PDF), com registro do download. */
    CreditorFileContent download(UUID statementId, UUID fileId);

    /**
     * Confere, sem efeito, que a pessoa pode responder a este demonstrativo (tipo do ator, {@code CREDITOR_STATEMENT_RESPOND}
     * e demonstrativo visível) — chamado antes da nova verificação do MFA, para quem não responde nunca gastar código.
     */
    void requireCanRespond(UUID statementId);

    /**
     * Aceite pela pessoa do credor ({@code CREDITOR_STATEMENT_RESPOND}): exige a nova verificação do MFA já feita na
     * mesma transação (comprovante de uso único, {@code stepUpProof} — SEG-F07) e os SHA-256 dos arquivos que ela viu — se mudaram, 409. Grava o termo.
     */
    CreditorStatementDetail acknowledge(UUID statementId, CreditorResponseCommand command);

    /** Contestação pela pessoa do credor: motivo obrigatório; nunca altera valores (a correção é por ajuste). */
    CreditorStatementDetail contest(UUID statementId, CreditorResponseCommand command);

    /**
     * @param note          observação do aceite ou motivo da contestação
     * @param fileSha256    hashes dos arquivos mostrados à pessoa (obrigatórios no aceite quando há arquivos)
     * @param clientIp      IP da requisição (já validado pelo filtro de proxies confiáveis)
     * @param stepUpProof   comprovante da nova verificação do MFA nesta transação (obrigatório no aceite; nulo na
     *                      contestação), conferido em {@link br.com.gestorcobranca.shared.security.StepUpProofs}
     */
    record CreditorResponseCommand(String note, List<String> fileSha256, String clientIp, String stepUpProof) {
    }

    /** @param direction {@code AGENCY_TO_CREDITOR} (a assessoria repassa), {@code CREDITOR_TO_AGENCY} (o credor paga) ou {@code NONE} */
    record CreditorStatementSummary(UUID id, int sequenceNumber, LocalDate periodStart, LocalDate periodEnd, String status,
                                    BigDecimal recovered, BigDecimal netTransfer, String direction, Instant sentAt,
                                    Instant respondedAt) {
    }

    record CreditorTotals(BigDecimal receivedByAgency, BigDecimal receivedByCreditor, BigDecimal reversed,
                          BigDecimal recovered, BigDecimal agencyHonorarium, BigDecimal commission, BigDecimal fixedFees,
                          BigDecimal expenses, BigDecimal adjustments, BigDecimal netTransfer) {
    }

    record CreditorStatementFile(UUID id, String format, String fileName, long size, String sha256, Instant createdAt) {
    }

    /**
     * @param respondedInPortal a resposta foi da própria pessoa do credor (e não registrada pela assessoria)
     */
    record CreditorStatementDetail(CreditorStatementSummary summary, CreditorTotals totals,
                                   List<CreditorStatementFile> files, BigDecimal transferred, BigDecimal outstanding,
                                   String responseNote, boolean respondedInPortal) {
    }

    record CreditorFileContent(String fileName, String contentType, long size, String sha256, InputStream content) {
    }

    record CreditorOpenPeriod(LocalDate periodStart, LocalDate asOf, LocalDate expectedPeriodEnd, BigDecimal recovered,
                              BigDecimal netTransfer, String direction, Instant calculatedAt) {
    }
}
