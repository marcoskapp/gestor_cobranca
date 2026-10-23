package br.com.gestorcobranca.payment.application.port.out;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Porta de registro de boleto (CNAB/API bancária). Sem provedor definido (issue #12): nenhum adaptador
 * em produção, e instruções {@code BOLETO} são recusadas até existir um.
 */
public interface BoletoProvider {

    BoletoResult register(BoletoRequest request);

    void cancel(String providerId);

    record BoletoRequest(UUID instructionId, BigDecimal amount, LocalDate dueDate, String payerDocument,
                         String payerName, String description) {

        @Override
        public String toString() {
            return "BoletoRequest[instructionId=" + instructionId + "]";
        }
    }

    /** @param digitableLine linha digitável; {@code barcode} código de barras; {@code ourNumber} nosso número */
    record BoletoResult(String providerId, String ourNumber, String digitableLine, String barcode, String pdfUrl) {
    }
}
