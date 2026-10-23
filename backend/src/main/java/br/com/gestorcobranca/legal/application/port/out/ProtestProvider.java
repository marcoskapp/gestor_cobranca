package br.com.gestorcobranca.legal.application.port.out;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Protesto eletrônico (cartórios de protesto / CENPROT): envio, acompanhamento e cancelamento. */
public interface ProtestProvider extends LegalProvider {

    SubmissionReceipt submit(ProtestRequest request);

    SubmissionStatus status(String reference);

    /** Pedido de cancelamento (baixa) após o pagamento. */
    SubmissionReceipt requestCancellation(String reference);

    /**
     * Dados do apontamento. A identificação do devedor é sensível: o adaptador não a registra em
     * log e a transmite somente ao provedor.
     */
    record ProtestRequest(java.util.UUID measureId, String debtorDocument, String debtorName, BigDecimal amount,
                          List<ProtestedTitle> titles) {
    }

    record ProtestedTitle(String externalId, String instrumentType, LocalDate dueDate, BigDecimal amount) {
    }
}
