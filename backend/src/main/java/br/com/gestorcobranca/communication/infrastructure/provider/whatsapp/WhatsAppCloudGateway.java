package br.com.gestorcobranca.communication.infrastructure.provider.whatsapp;

import br.com.gestorcobranca.communication.application.CommunicationProperties;
import br.com.gestorcobranca.communication.application.port.out.ChannelGateway;
import br.com.gestorcobranca.communication.application.port.out.ChannelGateway.ProviderTemplate;
import br.com.gestorcobranca.communication.domain.model.ProviderType;
import br.com.gestorcobranca.communication.domain.service.ProviderAddresses;
import br.com.gestorcobranca.shared.json.JsonCodec;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

import java.net.http.HttpClient;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Envio pela WhatsApp Cloud API (Meta): {@code POST /{phone-number-id}/messages} com token da conta.
 * Texto livre (dentro da janela de 24 h) ou template aprovado com parâmetros posicionais do corpo.
 * Template de autenticação (categoria {@code AUTHENTICATION}, código de acesso): o código vai no
 * componente {@code body} e no botão "copiar código" ({@code type=button}, {@code sub_type=url},
 * {@code index="0"}), como exige a Meta — o corpo e a validade são fixos no template.
 * <p>
 * Timeouts explícitos (conexão e leitura); 429, 5xx e falhas de rede são transitórios (o job tenta
 * de novo com backoff — não há retentativa dentro da transação do envio); demais 4xx são recusa
 * definitiva com o código do provedor ({@code WHATSAPP_<código>}). Métricas
 * {@code integration.calls{provider=whatsapp,operation=send_message,outcome}} e
 * {@code integration.calls.duration}. Token e destinatário nunca vão para log.
 */
@Component
class WhatsAppCloudGateway implements ChannelGateway {

    private static final Logger LOG = LoggerFactory.getLogger(WhatsAppCloudGateway.class);
    private static final String PROVIDER = "whatsapp";
    private static final String OPERATION = "send_message";

    private final RestClient client;
    private final JsonCodec json;
    private final MeterRegistry meterRegistry;

    WhatsAppCloudGateway(CommunicationProperties properties, JsonCodec json, MeterRegistry meterRegistry) {
        CommunicationProperties.WhatsApp config = properties.whatsapp();
        HttpClient http = HttpClient.newBuilder().connectTimeout(config.connectTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(config.readTimeout());
        this.client = RestClient.builder().requestFactory(factory).baseUrl(config.apiBaseUrl()).build();
        this.json = json;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public ProviderType provider() {
        return ProviderType.WHATSAPP_CLOUD;
    }

    @Override
    public SendResult send(OutboundRequest request) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "error";
        try {
            String response = client.post()
                    .uri("/{phoneNumberId}/messages", request.senderAddress())
                    .header("Authorization", "Bearer " + request.credentials().get("accessToken"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(json.toJson(body(request)))
                    .retrieve()
                    .body(String.class);
            JsonNode node = json.mapper().readTree(response == null ? "{}" : response);
            String id = node.path("messages").path(0).path("id").asString("");
            if (id.isBlank()) {
                outcome = "invalid_response";
                throw new TransientSendException("Resposta do WhatsApp sem id da mensagem");
            }
            outcome = "success";
            return SendResult.accepted(id);
        } catch (RestClientResponseException e) {
            HttpStatusCode status = e.getStatusCode();
            if (status.value() == 429 || status.is5xxServerError()) {
                outcome = "transient";
                throw new TransientSendException("WhatsApp HTTP " + status.value(), e);
            }
            outcome = "rejected";
            String code = errorCode(e.getResponseBodyAsString());
            LOG.atWarn().addKeyValue("provider", PROVIDER).addKeyValue("status", status.value())
                    .addKeyValue("errorCode", code).log("WhatsApp recusou a mensagem");
            return SendResult.rejected("WHATSAPP_" + code, "HTTP " + status.value());
        } catch (ResourceAccessException e) {
            outcome = "timeout";
            throw new TransientSendException("Falha de comunicação com o WhatsApp", e);
        } finally {
            meterRegistry.counter("integration.calls", "provider", PROVIDER, "operation", OPERATION, "outcome", outcome)
                    .increment();
            sample.stop(meterRegistry.timer("integration.calls.duration", "provider", PROVIDER, "operation", OPERATION));
        }
    }

    private static Map<String, Object> body(OutboundRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messaging_product", "whatsapp");
        body.put("recipient_type", "individual");
        body.put("to", ProviderAddresses.toWhatsApp(request.recipient()));
        if (request.template() != null) {
            ProviderTemplate provider = request.template();
            List<Map<String, Object>> parameters = provider.parameters().stream()
                    .map(WhatsAppCloudGateway::textParameter)
                    .toList();
            Map<String, Object> template = new LinkedHashMap<>();
            template.put("name", provider.name());
            template.put("language", Map.of("code", provider.language()));
            if (provider.authentication()) {
                template.put("components", List.of(
                        Map.of("type", "body", "parameters", parameters),
                        copyCodeButton(parameters.getFirst())));
            } else if (!parameters.isEmpty()) {
                template.put("components", List.of(Map.of("type", "body", "parameters", parameters)));
            }
            body.put("type", "template");
            body.put("template", template);
        } else {
            body.put("type", "text");
            body.put("text", Map.of("preview_url", false, "body", request.text()));
        }
        return body;
    }

    private static Map<String, Object> textParameter(String value) {
        return Map.of("type", "text", "text", value.isEmpty() ? "-" : value);
    }

    /** Botão "copiar código" do template de autenticação: a Meta o recebe como botão de URL de índice 0. */
    private static Map<String, Object> copyCodeButton(Map<String, Object> codeParameter) {
        Map<String, Object> button = new LinkedHashMap<>();
        button.put("type", "button");
        button.put("sub_type", "url");
        button.put("index", "0");
        button.put("parameters", List.of(codeParameter));
        return button;
    }

    private String errorCode(String responseBody) {
        try {
            JsonNode error = json.mapper().readTree(responseBody == null || responseBody.isBlank() ? "{}" : responseBody)
                    .path("error");
            String code = error.path("code").asString("");
            return code.isBlank() ? "UNKNOWN" : code;
        } catch (RuntimeException e) {
            return "UNKNOWN";
        }
    }
}
