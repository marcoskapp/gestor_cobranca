package br.com.gestorcobranca.payment.application.port.in;

import br.com.gestorcobranca.debt.application.port.in.ReversalHoldUseCases.HoldFilter;
import br.com.gestorcobranca.debt.application.port.in.ReversalHoldUseCases.HoldView;
import br.com.gestorcobranca.debt.application.port.in.ReversalHoldUseCases.ReleaseCommand;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Aba "Estornos em conferência" de "Baixas informadas pelo credor" (H07 parte 3): a retenção da cobrança (debt) com o
 * pagamento e o estorno que a originaram (payment). {@code PAYMENT_VIEW} para ver; liberar com {@code PAYMENT_REVIEW}
 * (nunca quem estornou nem quem informou). Ator do credor, integração, assistente e devedor recusados (403). Sem a
 * justificativa do estorno, documento, contato ou comprovante; o token só com {@code PUBLIC_API_TOKEN_VIEW}.
 */
public interface PaymentReversalHoldsUseCases {

    PageResponse<HeldReversalItem> search(HoldFilter filter, PageQuery page);

    /** Fora do escopo, de outra assessoria ou inexistente: o mesmo 404 (RS-117). */
    HeldReversalItem get(UUID holdId);

    /** Retenções do pagamento estornado (bloco na ficha do pagamento). */
    List<HeldReversalItem> forPayment(UUID paymentId);

    HeldReversalItem release(UUID holdId, ReleaseCommand command);

    /**
     * @param channel        INTEGRATION, CREDITOR_PORTAL ou OPERATOR (quem informou o pagamento)
     * @param token          token que informou (canal INTEGRATION e {@code PUBLIC_API_TOKEN_VIEW}); nulo nos demais
     * @param reversalSource MANUAL ou PROVIDER
     */
    record HeldReversalItem(HoldView hold, BigDecimal amount, Instant paidAt, String channel,
                            CreditorReportedPaymentsUseCases.TokenRef token, String reversalSource, Instant reversedAt) {
    }
}
