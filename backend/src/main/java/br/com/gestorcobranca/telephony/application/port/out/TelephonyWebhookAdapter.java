package br.com.gestorcobranca.telephony.application.port.out;

import br.com.gestorcobranca.telephony.domain.model.ProviderType;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Validação e leitura das notificações de chamada de um provedor. A assinatura é validada sobre a
 * requisição bruta (corpo e, quando o provedor assina, a URL pública com a consulta), com o segredo
 * da conta e em tempo constante, antes de qualquer interpretação. Provedores que assinam o instante
 * da entrega (padrão {@code t=<epoch>,v1=<hmac>}) devem recusar assinaturas fora da janela de
 * tolerância (replay).
 * <p>
 * Provedores que pedem instruções por webhook (TwiML/Plivo XML: atendimento, chamada recebida,
 * aviso ao devedor) declaram {@link #instructionRequest}; o domínio decide a {@link CallInstruction}
 * e o adaptador a traduz em {@link #render}. As URLs geradas pelo adaptador carregam só
 * referências opacas (ids internos), nunca número de telefone ou nome.
 */
public interface TelephonyWebhookAdapter {

    ProviderType provider();

    /** @param now instante de recebimento (janela de tolerância da assinatura) */
    boolean validSignature(Map<String, String> credentials, WebhookRequest request, Instant now);

    /** @throws IllegalArgumentException corpo malformado */
    List<ProviderCallEvent> parse(WebhookRequest request);

    /** O provedor espera instruções da chamada na resposta desta requisição. */
    default Optional<InstructionRequest> instructionRequest(WebhookRequest request) {
        return Optional.empty();
    }

    /** Resposta do webhook com a instrução decidida pelo domínio (padrão: corpo vazio). */
    default WebhookReply render(WebhookRequest request, CallInstruction instruction) {
        return WebhookReply.EMPTY;
    }

    /**
     * Endereços a cadastrar no console do provedor (ex.: URL de voz do número para chamadas
     * recebidas), como sufixos acrescentados ao endereço do webhook da conta.
     */
    default List<ConsoleEndpoint> consoleEndpoints() {
        return List.of(new ConsoleEndpoint("events", "Eventos de chamada", "POST", ""));
    }

    /**
     * Requisição de webhook recebida.
     *
     * @param url        URL pública exatamente como o provedor a chamou (base pública + caminho +
     *                   consulta crua), usada nas assinaturas que cobrem a URL
     * @param query      parâmetros da consulta (decodificados, primeiro valor de cada nome)
     * @param headers    cabeçalhos com nomes em minúsculas (somente {@code x-*})
     * @param body       corpo bruto
     * @param contentType tipo do corpo (pode faltar)
     * @param receivedAt instante de recebimento
     */
    record WebhookRequest(String url, Map<String, String> query, Map<String, String> headers, byte[] body,
                          String contentType, Instant receivedAt) {

        public WebhookRequest {
            query = query == null ? Map.of() : Map.copyOf(query);
            headers = headers == null ? Map.of() : Map.copyOf(headers);
            body = body == null ? new byte[0] : body;
        }

        public static WebhookRequest of(Map<String, String> headers, byte[] body, Instant receivedAt) {
            return new WebhookRequest(null, Map.of(), headers, body, null, receivedAt);
        }
    }

    /**
     * Pedido de instruções.
     *
     * @param kind           o que o provedor está pedindo
     * @param providerCallId id da chamada no provedor (pode faltar)
     * @param callReference  id interno (referência do cliente na URL; pode faltar na chamada recebida)
     * @param operatorId     operador da conexão ({@link Kind#CONNECT_OPERATOR})
     */
    record InstructionRequest(Kind kind, String providerCallId, UUID callReference, UUID operatorId) {

        public enum Kind {
            /** Operador atendeu (clique para ligar, operador primeiro): discar para o devedor. */
            DIAL_DEBTOR,
            /** Atendimento ou chamada recebida: conexão ao operador, espera (AMD) ou encerramento. */
            CALL_ANSWERED,
            /** Transferência pedida pela conexão ao operador ({@code bridge}). */
            CONNECT_OPERATOR,
            /** Aviso ao devedor ao atender. */
            ANNOUNCE,
            /** Mensagem informativa de abandono e encerramento (pedida pelo próprio {@code hangup}). */
            ABANDON,
            /** Encerrar (fim de um passo sem continuação). */
            END
        }
    }

    /** Corpo da resposta do webhook. */
    record WebhookReply(String contentType, String body) {

        public static final WebhookReply EMPTY = new WebhookReply(null, null);
    }

    /**
     * @param purpose identificador estável ({@code events}, {@code inbound}, {@code hangup}…)
     * @param label   descrição para a tela
     * @param method  método HTTP a configurar no console
     * @param suffix  acrescentado ao endereço do webhook da conta (ex.: {@code ?k=inbound})
     */
    record ConsoleEndpoint(String purpose, String label, String method, String suffix) {
    }
}
