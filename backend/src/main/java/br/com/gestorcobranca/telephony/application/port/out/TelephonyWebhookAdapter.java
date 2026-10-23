package br.com.gestorcobranca.telephony.application.port.out;

import br.com.gestorcobranca.telephony.domain.model.ProviderType;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Validação e leitura das notificações de chamada de um provedor. A assinatura é validada sobre o
 * corpo bruto, com o segredo da conta e em tempo constante, antes de qualquer interpretação.
 * Provedores que assinam o instante da entrega (padrão {@code t=<epoch>,v1=<hmac>}) devem recusar
 * assinaturas fora da janela de tolerância (replay).
 */
public interface TelephonyWebhookAdapter {

    ProviderType provider();

    /**
     * @param headers cabeçalhos da requisição com nomes em minúsculas
     * @param now     instante de recebimento (janela de tolerância da assinatura)
     */
    boolean validSignature(Map<String, String> credentials, Map<String, String> headers, byte[] body, Instant now);

    /** @throws IllegalArgumentException corpo malformado */
    List<ProviderCallEvent> parse(byte[] body);
}
