package br.com.gestorcobranca.telephony.infrastructure.provider.twilio;

import br.com.gestorcobranca.telephony.application.TelephonyProperties;
import org.springframework.stereotype.Component;

import java.util.Locale;

import static br.com.gestorcobranca.telephony.infrastructure.provider.WebhookForms.xml;

/**
 * Documentos TwiML usados pelo adaptador da Twilio (verbos {@code <Say>}, {@code <Pause>},
 * {@code <Dial>} com {@code <Client>}/{@code <Sip>}/{@code <Number>} e {@code <Hangup>}). Enviados
 * inline no parâmetro {@code Twiml} da API de chamadas (até 4.000 caracteres) ou na resposta dos
 * webhooks que pedem instruções. Texto falado em pt-BR pela voz configurada.
 */
@Component
class TwilioTwiml {

    static final String CONTENT_TYPE = "application/xml";
    static final int MAX_INLINE = 4000;

    private final TelephonyProperties.Twilio config;
    private final TelephonyProperties.Messages messages;

    TwilioTwiml(TelephonyProperties properties) {
        this.config = properties.twilio();
        this.messages = properties.messages();
    }

    /**
     * Clique para ligar, perna do operador: ao atender, disca para o devedor. O aviso de gravação é
     * tocado ao devedor pelo {@code url} do {@code <Number>} (antes de conectar).
     */
    String originate(String destination, String callerId, int ringTimeoutSeconds, boolean record,
                     String customerStatusUrl, String recordingUrl, String announceUrl) {
        StringBuilder dial = new StringBuilder("<Dial callerId=\"").append(xml(callerId)).append("\" timeout=\"")
                .append(clampTimeout(ringTimeoutSeconds)).append('"');
        if (record) {
            dial.append(" record=\"record-from-answer-dual\" recordingStatusCallback=\"").append(xml(recordingUrl))
                    .append("\" recordingStatusCallbackEvent=\"completed\"");
        }
        dial.append("><Number statusCallbackEvent=\"ringing answered completed\" statusCallback=\"")
                .append(xml(customerStatusUrl)).append('"');
        if (announceUrl != null) {
            dial.append(" url=\"").append(xml(announceUrl)).append('"');
        }
        dial.append('>').append(xml(destination)).append("</Number></Dial>");
        return response(dial.toString());
    }

    /**
     * Discador: chamada atendida aguardando a detecção de secretária e a conexão ao operador (a API
     * substitui este documento no {@code bridge}); esgotada a espera, a chamada termina.
     */
    String dialerHold(boolean announce, int holdSeconds) {
        return response((announce ? say(messages.recordingNotice()) : "") + "<Pause length=\"" + holdSeconds + "\"/>");
    }

    /** Conexão ao ramal do operador. */
    String connect(String endpoint, String callerId, boolean record, boolean announce, String recordingUrl) {
        StringBuilder dial = new StringBuilder(announce ? say(messages.recordingNotice()) : "");
        dial.append("<Dial timeout=\"20\"");
        if (callerId != null && !callerId.isBlank()) {
            dial.append(" callerId=\"").append(xml(callerId)).append('"');
        }
        if (record && recordingUrl != null) {
            dial.append(" record=\"record-from-answer-dual\" recordingStatusCallback=\"").append(xml(recordingUrl))
                    .append("\" recordingStatusCallbackEvent=\"completed\"");
        }
        dial.append('>').append(noun(endpoint)).append("</Dial>");
        return response(dial.toString());
    }

    /** Abandono: mensagem informativa e encerramento. */
    String abandon() {
        return response(say(messages.abandonMessage()) + "<Hangup/>");
    }

    /** Aviso de gravação ao devedor (TwiML do {@code url} do {@code <Number>}: sem {@code <Dial>}). */
    String announce() {
        return response(say(messages.recordingNotice()));
    }

    String hangup() {
        return response("<Hangup/>");
    }

    String empty() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Response/>";
    }

    /**
     * Substantivo do {@code <Dial>} para o ramal: {@code client:<identidade>} → {@code <Client>},
     * {@code sip:…} → {@code <Sip>}, E.164 → {@code <Number>}.
     */
    static String noun(String endpoint) {
        String value = endpoint == null ? "" : endpoint.trim();
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.startsWith("client:")) {
            return "<Client>" + xml(value.substring("client:".length())) + "</Client>";
        }
        if (lower.startsWith("sip:")) {
            return "<Sip>" + xml(value) + "</Sip>";
        }
        return "<Number>" + xml(value) + "</Number>";
    }

    private String say(String text) {
        return "<Say voice=\"" + xml(config.sayVoice()) + "\" language=\"" + xml(config.sayLanguage()) + "\">"
                + xml(text) + "</Say>";
    }

    private static int clampTimeout(int seconds) {
        // <Dial timeout>: mínimo 5 s, máximo 600 s
        return Math.max(5, Math.min(600, seconds));
    }

    private static String response(String verbs) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Response>" + verbs + "</Response>";
    }
}
