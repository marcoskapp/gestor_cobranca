package br.com.gestorcobranca.telephony.infrastructure.provider.plivo;

import br.com.gestorcobranca.telephony.application.TelephonyProperties;
import org.springframework.stereotype.Component;

import java.util.Locale;

import static br.com.gestorcobranca.telephony.infrastructure.provider.WebhookForms.xml;

/**
 * Documentos Plivo XML usados pelo adaptador da Plivo ({@code <Speak>}, {@code <Wait>},
 * {@code <Redirect>}, {@code <Record>}, {@code <Dial>} com {@code <User>}/{@code <Number>} e
 * {@code <Hangup>}), devolvidos nas respostas de {@code answer_url}, {@code aleg_url} e demais
 * URLs que pedem instruções. Texto falado em pt-BR pela voz configurada.
 */
@Component
class PlivoXml {

    static final String CONTENT_TYPE = "application/xml";
    /** Gravação de sessão: limite da própria chamada (4 h, padrão do {@code time_limit}). */
    private static final int RECORD_MAX_SECONDS = 14_400;

    private final TelephonyProperties.Plivo config;
    private final TelephonyProperties.Messages messages;

    PlivoXml(TelephonyProperties properties) {
        this.config = properties.plivo();
        this.messages = properties.messages();
    }

    /**
     * Clique para ligar, perna do operador atendida: disca para o devedor. Gravação a partir do
     * atendimento do devedor ({@code startOnDialAnswer}); aviso tocado ao devedor ao atender
     * ({@code confirmSound}); eventos da perna do devedor em {@code callbackUrl}; fim do
     * {@code <Dial>} notificado em {@code action} sem redirecionar.
     */
    String dialDebtor(String destination, String callerId, int ringTimeoutSeconds, String recordingUrl,
                      String dialEventsUrl, String dialEndUrl, String announceUrl) {
        StringBuilder xml = new StringBuilder();
        if (recordingUrl != null) {
            xml.append("<Record startOnDialAnswer=\"true\" redirect=\"false\" fileFormat=\"mp3\" maxLength=\"")
                    .append(RECORD_MAX_SECONDS).append("\" callbackUrl=\"").append(xml(recordingUrl))
                    .append("\" callbackMethod=\"POST\"/>");
        }
        xml.append("<Dial callerId=\"").append(xml(digits(callerId))).append("\" timeout=\"")
                .append(Math.max(5, ringTimeoutSeconds)).append("\" redirect=\"false\" action=\"").append(xml(dialEndUrl))
                .append("\" method=\"POST\" callbackUrl=\"").append(xml(dialEventsUrl)).append("\" callbackMethod=\"POST\"");
        if (announceUrl != null) {
            xml.append(" confirmSound=\"").append(xml(announceUrl)).append('"');
        }
        xml.append("><Number>").append(xml(digits(destination))).append("</Number></Dial>");
        return response(xml.toString());
    }

    /**
     * Discador: atendida aguardando o veredito do AMD. Gravação da sessão inteira, aviso de
     * gravação, espera em silêncio pelo tempo da detecção e, sem veredito de máquina, novo pedido
     * de instruções ({@code <Redirect>}).
     */
    String hold(String recordingUrl, boolean announce, int waitSeconds, String continueUrl) {
        StringBuilder xml = new StringBuilder(record(recordingUrl));
        if (announce) {
            xml.append(speak(messages.recordingNotice()));
        }
        xml.append("<Wait length=\"").append(Math.max(1, waitSeconds)).append("\" silence=\"true\"/>")
                .append("<Redirect method=\"POST\">").append(xml(continueUrl)).append("</Redirect>");
        return response(xml.toString());
    }

    /** Conexão ao ramal do operador (gravação e aviso quando a chamada ainda não os começou). */
    String connect(String endpoint, String callerId, String recordingUrl, boolean announce) {
        StringBuilder xml = new StringBuilder(record(recordingUrl));
        if (announce) {
            xml.append(speak(messages.recordingNotice()));
        }
        xml.append("<Dial timeout=\"20\"");
        if (callerId != null && !callerId.isBlank() && !endpoint.toLowerCase(Locale.ROOT).startsWith("sip:")) {
            xml.append(" callerId=\"").append(xml(digits(callerId))).append('"');
        }
        xml.append('>').append(noun(endpoint)).append("</Dial>");
        return response(xml.toString());
    }

    String abandon() {
        return response(speak(messages.abandonMessage()) + "<Hangup/>");
    }

    String announce() {
        return response(speak(messages.recordingNotice()));
    }

    String hangup() {
        return response("<Hangup/>");
    }

    String empty() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Response></Response>";
    }

    /** {@code sip:…} → {@code <User>}; telefone → {@code <Number>} (dígitos, sem +). */
    static String noun(String endpoint) {
        String value = endpoint == null ? "" : endpoint.trim();
        if (value.toLowerCase(Locale.ROOT).startsWith("sip:")) {
            return "<User>" + xml(value) + "</User>";
        }
        return "<Number>" + xml(digits(value)) + "</Number>";
    }

    /** Números no formato da Plivo: E.164 sem o {@code +} (ex.: {@code 5511999990000}). */
    static String digits(String number) {
        return number == null ? "" : number.trim().startsWith("+") ? number.trim().substring(1) : number.trim();
    }

    private static String record(String recordingUrl) {
        if (recordingUrl == null) {
            return "";
        }
        return "<Record recordSession=\"true\" redirect=\"false\" fileFormat=\"mp3\" maxLength=\"" + RECORD_MAX_SECONDS
                + "\" callbackUrl=\"" + xml(recordingUrl) + "\" callbackMethod=\"POST\"/>";
    }

    private String speak(String text) {
        return "<Speak voice=\"" + xml(config.speakVoice()) + "\" language=\"" + xml(config.speakLanguage()) + "\">"
                + xml(text) + "</Speak>";
    }

    private static String response(String elements) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Response>" + elements + "</Response>";
    }
}
