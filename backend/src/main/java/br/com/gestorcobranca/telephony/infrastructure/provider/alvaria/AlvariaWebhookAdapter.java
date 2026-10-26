package br.com.gestorcobranca.telephony.infrastructure.provider.alvaria;

import br.com.gestorcobranca.shared.json.JsonCodec;
import br.com.gestorcobranca.telephony.application.TelephonyProperties;
import br.com.gestorcobranca.telephony.application.port.out.ProviderCallEvent;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyWebhookAdapter;
import br.com.gestorcobranca.telephony.domain.model.ProviderType;
import br.com.gestorcobranca.telephony.infrastructure.provider.CanonicalCallEvents;
import br.com.gestorcobranca.telephony.infrastructure.provider.TimestampedHmac;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Eventos de chamada da Alvaria CX. <b>Formato e autenticação a confirmar com a Alvaria</b> (anexo A
 * do desenho): não há documentação pública das notificações de chamada. Até a confirmação, a
 * entrada é o <b>formato canônico do Gestor</b> ({@link CanonicalCallEvents}), assinado com
 * {@code X-Gestor-Signature: t=<epoch>,v1=<HMAC-SHA256(webhookSecret, t + "." + corpo)>} — publicado
 * pela própria Alvaria, se a plataforma permitir configurar o corpo e a assinatura das notificações,
 * ou por um conector que traduza os eventos de CTI dela (ex.: início/fim/atualização de chamada).
 * Se a Alvaria tiver formato e assinatura próprios, a tradução entra aqui (só este adaptador muda;
 * o domínio recebe sempre {@link ProviderCallEvent}).
 */
@Component
class AlvariaWebhookAdapter implements TelephonyWebhookAdapter {

    static final String HEADER = "x-gestor-signature";

    private final JsonCodec json;
    private final Duration tolerance;

    AlvariaWebhookAdapter(JsonCodec json, TelephonyProperties properties) {
        this.json = json;
        this.tolerance = properties.webhook().maxClockSkew();
    }

    @Override
    public ProviderType provider() {
        return ProviderType.ALVARIA;
    }

    @Override
    public boolean validSignature(Map<String, String> credentials, Map<String, String> headers, byte[] body, Instant now) {
        return TimestampedHmac.valid(credentials.get("webhookSecret"), headers == null ? null : headers.get(HEADER), body,
                now, tolerance);
    }

    @Override
    public List<ProviderCallEvent> parse(byte[] body) {
        return CanonicalCallEvents.parse(json, body);
    }
}
