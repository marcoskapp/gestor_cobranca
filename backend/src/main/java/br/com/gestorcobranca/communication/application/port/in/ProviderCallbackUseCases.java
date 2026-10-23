package br.com.gestorcobranca.communication.application.port.in;

import br.com.gestorcobranca.communication.application.port.out.ProviderWebhookAdapter.InboundMessage;
import br.com.gestorcobranca.communication.application.port.out.ProviderWebhookAdapter.StatusUpdate;

import java.util.UUID;

/**
 * <strong>Uso interno do módulo (webhooks e sandbox); não usar de outros módulos.</strong>
 * Processa notificações de provedor já autenticadas, no tenant da conta. Idempotente pelo id do
 * provedor: mensagem já recebida e status já aplicado não têm novo efeito.
 */
public interface ProviderCallbackUseCases {

    /** @return true se a mensagem foi registrada agora (false = reentrega) */
    boolean inbound(UUID channelAccountId, InboundMessage message);

    /** @return true se o status da mensagem mudou */
    boolean status(UUID channelAccountId, StatusUpdate update);
}
