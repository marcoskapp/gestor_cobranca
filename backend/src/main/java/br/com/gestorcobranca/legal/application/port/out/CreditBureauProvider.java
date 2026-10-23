package br.com.gestorcobranca.legal.application.port.out;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Birôs de negativação: inclusão (após a comunicação prévia — CDC art. 43 §2º, conferida pelo caso
 * de uso), acompanhamento e baixa.
 */
public interface CreditBureauProvider extends LegalProvider {

    SubmissionReceipt include(ListingRequest request);

    SubmissionStatus status(String reference);

    SubmissionReceipt remove(String reference);

    record ListingRequest(UUID measureId, String debtorDocument, String debtorName, BigDecimal amount,
                          LocalDate oldestDueDate, LocalDate priorNoticeOn) {
    }
}
