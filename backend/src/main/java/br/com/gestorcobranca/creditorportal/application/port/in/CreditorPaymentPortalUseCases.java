package br.com.gestorcobranca.creditorportal.application.port.in;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Pagamento direto informado pela pessoa do credor no portal (fatia 6): busca dos próprios títulos pelo número, informe
 * com comprovante e código do autenticador (sempre em análise pela assessoria) e a lista dos informes com a situação e o
 * motivo da recusa. Records próprios, com a lista de campos permitidos ao credor.
 */
public interface CreditorPaymentPortalUseCases {

    List<TitleMatch> findTitles(String number);

    /**
     * Verifica o comprovante fora de transação e, numa transação só, confere sem efeito, verifica o código do
     * autenticador e grava o informe.
     */
    ReportedPayment report(ReportCommand command, Evidence evidence, String idempotencyKey);

    List<ReportedPayment> reported();

    record TitleMatch(String number, Integer installmentNumber, LocalDate dueDate, BigDecimal openBalance,
                      boolean underAgreement) {
    }

    /** @param method PIX, BOLETO, CARD, TRANSFER, CASH ou OTHER; @param code código de 6 dígitos do autenticador */
    record ReportCommand(List<String> titleNumbers, BigDecimal amount, LocalDate paidOn, String method, String reference,
                         String note, String code) {
    }

    record Evidence(String fileName, String contentType, long size, InputStream content) {
    }

    /**
     * @param status          IN_REVIEW, CONFIRMED, REJECTED ou REVERSED
     * @param rejectionReason PROOF_INVALID, AMOUNT_OR_DATE_MISMATCH, WRONG_TITLES, DUPLICATE ou OTHER
     */
    record ReportedPayment(UUID id, String status, BigDecimal amount, LocalDate paidOn, String method,
                           List<String> titleNumbers, String reference, Instant informedAt, Instant decidedAt,
                           String rejectionReason) {
    }
}
