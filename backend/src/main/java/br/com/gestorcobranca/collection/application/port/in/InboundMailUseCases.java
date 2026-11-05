package br.com.gestorcobranca.collection.application.port.in;

import java.time.Instant;
import java.util.List;

/**
 * Entrada das respostas do credor por e-mail, chamada pelos adaptadores de recebimento (SES/SQS em produção, pasta
 * local em desenvolvimento e sandbox). Idempotente pelo id do provedor: a reentrega da fila não duplica nada.
 */
public interface InboundMailUseCases {

    /**
     * Registra o e-mail: no pedido do endereço de resposta (aguardando o analista ou em quarentena) ou, sem pedido
     * correspondente, na lista técnica de e-mails sem rota. Nunca descarta em silêncio.
     */
    Outcome receive(InboundMessage message);

    /**
     * @param providerMessageId  id do e-mail no provedor (SES {@code mail.messageId} ou nome do arquivo local)
     * @param raw                e-mail original (RFC 5322), como chegou
     * @param envelopeRecipients destinatários do envelope SMTP (o endereço de resposta do pedido está aqui)
     * @param verdicts           veredictos do provedor: PASS, FAIL, GRAY, PROCESSING_FAILED ou DISABLED
     * @param providerLocator    onde o provedor guardou o original (chave no bucket de entrada ou caminho local)
     */
    record InboundMessage(String providerMessageId, byte[] raw, List<String> envelopeRecipients, Verdicts verdicts,
                          Instant receivedAt, String providerLocator) {

        public InboundMessage {
            envelopeRecipients = envelopeRecipients == null ? List.of() : List.copyOf(envelopeRecipients);
        }
    }

    record Verdicts(String spf, String dkim, String dmarc, String virus, String spam) {
    }

    enum Outcome {
        /** No pedido, aguardando o analista confirmar a posição do credor. */
        ACCEPTED,
        /** No pedido, em quarentena (visível à gestão). */
        QUARANTINED,
        /** Sem pedido correspondente: registro técnico global. */
        UNROUTED,
        /** Já registrado (reentrega). */
        DUPLICATE
    }
}
