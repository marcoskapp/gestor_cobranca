package br.com.gestorcobranca.payment.application.service;

import br.com.gestorcobranca.payment.domain.model.Payment;
import br.com.gestorcobranca.shared.crypto.BlindIndex;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Forma anonimizada da referência do pagamento externo (SEG-20261124-H04, decisão 1463): HMAC com a chave de blind
 * index e a assessoria, nunca hash sem chave (a referência pode ser um CPF ou um nome, reversíveis por força bruta).
 * Determinística, para a conciliação do extrato reconhecer o pagamento já baixado. Girar a chave de blind index faz o
 * extrato deixar de reconhecer as referências já anonimizadas (mesmo efeito da busca de devedor pelo documento).
 */
@Component
class PaymentReferenceDigest {

    static final String PURPOSE = "payment.external_reference";

    private final BlindIndex blindIndex;

    PaymentReferenceDigest(BlindIndex blindIndex) {
        this.blindIndex = blindIndex;
    }

    String digest(UUID tenantId, String reference) {
        return blindIndex.of(tenantId, PURPOSE, reference);
    }

    String anonymized(UUID tenantId, String reference) {
        return Payment.ANONYMIZED_REFERENCE_PREFIX + digest(tenantId, reference);
    }
}
