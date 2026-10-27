package br.com.gestorcobranca.telephony.infrastructure.provider.twilio;

import br.com.gestorcobranca.telephony.application.port.out.CallInstruction;
import br.com.gestorcobranca.telephony.application.port.out.ProviderCallEvent;
import br.com.gestorcobranca.telephony.application.port.out.ProviderCallEvent.Type;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyWebhookAdapter;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyWebhookAdapter.InstructionRequest.Kind;
import br.com.gestorcobranca.telephony.domain.model.ProviderType;
import br.com.gestorcobranca.telephony.infrastructure.provider.WebhookForms;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Webhooks da Twilio (anexo C do desenho). Autenticidade por {@code X-Twilio-Signature} (HMAC-SHA1
 * com o Auth Token sobre a URL pública com a consulta + parâmetros do POST ordenados; tempo
 * constante). A Twilio não assina instante: replay é contido pelo recibo do corpo, pela
 * idempotência por evento ({@code CallSid} + status) e pela janela de eventos ({@code Timestamp}
 * assinado no corpo).
 * <p>
 * Tipo do callback pela consulta {@code k} das URLs geradas pelo adaptador:
 * <ul>
 *   <li>{@code status} — eventos de andamento por perna ({@code leg=agent|customer|dialer|inbound}):
 *       {@code queued/initiated} ignorados; {@code ringing} → {@code RINGING}; {@code in-progress} →
 *       {@code ANSWERED} (exceto perna do operador, e do discador com AMD, cujo atendimento vem do
 *       veredito); {@code completed} → {@code COMPLETED}; {@code busy}, {@code no-answer},
 *       {@code failed} ({@code SipResponseCode} como causa), {@code canceled};</li>
 *   <li>{@code amd} — veredito assíncrono ({@code AnsweredBy}: {@code human} → pessoa;
 *       {@code machine_*}/{@code fax} → máquina; {@code unknown} → desconhecido, tratado como pessoa);</li>
 *   <li>{@code recording} — {@code RecordingStatus=completed} → gravação disponível ({@code RecordingSid});</li>
 *   <li>{@code inbound} — URL de voz do número (chamada recebida): {@code INBOUND} e instruções na resposta;</li>
 *   <li>{@code announce} — TwiML do aviso de gravação ao devedor.</li>
 * </ul>
 */
@Component
class TwilioWebhookAdapter implements TelephonyWebhookAdapter {

    static final String HEADER = "x-twilio-signature";

    private final TwilioTwiml twiml;

    TwilioWebhookAdapter(TwilioTwiml twiml) {
        this.twiml = twiml;
    }

    @Override
    public ProviderType provider() {
        return ProviderType.TWILIO;
    }

    @Override
    public boolean validSignature(Map<String, String> credentials, WebhookRequest request, Instant now) {
        Map<String, List<String>> form;
        try {
            form = WebhookForms.parse(request.body());
        } catch (IllegalArgumentException e) {
            return false;
        }
        return TwilioSecurity.validSignature(credentials.get("authToken"), request.url(), form,
                request.headers().get(HEADER));
    }

    @Override
    public List<ProviderCallEvent> parse(WebhookRequest request) {
        Map<String, List<String>> form = WebhookForms.parse(request.body());
        String kind = request.query().getOrDefault(WebhookForms.KIND, "");
        UUID ref = WebhookForms.uuid(request.query(), WebhookForms.REFERENCE);
        String callSid = WebhookForms.first(form, "CallSid");
        if (callSid == null || callSid.isBlank()) {
            throw new IllegalArgumentException("Webhook da Twilio sem CallSid");
        }
        Instant at = timestamp(WebhookForms.first(form, "Timestamp"), request.receivedAt());
        return switch (kind) {
            case "status" -> status(request.query(), form, callSid, ref, at);
            case "amd" -> amd(form, callSid, ref, request.receivedAt());
            case "recording" -> recording(form, callSid, ref, at);
            case "inbound" -> List.of(new ProviderCallEvent(callSid + ":inbound", Type.INBOUND, callSid, null,
                    request.receivedAt(), null, null, null, WebhookForms.first(form, "From"), null, null, null, true));
            default -> List.of();
        };
    }

    private static List<ProviderCallEvent> status(Map<String, String> query, Map<String, List<String>> form,
                                                  String callSid, UUID ref, Instant at) {
        String leg = query.getOrDefault(WebhookForms.LEG, "");
        boolean amd = "1".equals(query.get(WebhookForms.AMD));
        String status = lower(WebhookForms.first(form, "CallStatus"));
        // perna do devedor no clique para ligar: a chamada do domínio é a mãe (perna do operador)
        String providerCallId = "customer".equals(leg) ? WebhookForms.first(form, "ParentCallSid") : callSid;
        Type type = switch (status) {
            case "ringing" -> "agent".equals(leg) ? null : Type.RINGING;
            case "in-progress", "answered" -> "agent".equals(leg) || ("dialer".equals(leg) && amd) ? null : Type.ANSWERED;
            case "completed" -> Type.COMPLETED;
            case "busy" -> Type.BUSY;
            case "no-answer" -> Type.NO_ANSWER;
            case "failed" -> Type.FAILED;
            case "canceled" -> Type.CANCELLED;
            default -> null;
        };
        if (type == null) {
            return List.of();
        }
        String cause = switch (type) {
            case FAILED -> "TWILIO_SIP_" + safe(WebhookForms.first(form, "SipResponseCode"));
            case BUSY, NO_ANSWER, CANCELLED -> "agent".equals(leg) ? "AGENT_" + type.name() : null;
            default -> null;
        };
        return List.of(new ProviderCallEvent(callSid + ":" + status, type, providerCallId, ref, at, null, null, cause,
                null, null, null));
    }

    private static List<ProviderCallEvent> amd(Map<String, List<String>> form, String callSid, UUID ref,
                                               Instant receivedAt) {
        String answeredBy = lower(WebhookForms.first(form, "AnsweredBy"));
        String amd;
        if ("human".equals(answeredBy)) {
            amd = "HUMAN";
        } else if (answeredBy.startsWith("machine") || "fax".equals(answeredBy)) {
            amd = "MACHINE";
        } else {
            amd = "UNKNOWN";
        }
        Instant at = receivedAt;
        try {
            long ms = Long.parseLong(safe(WebhookForms.first(form, "MachineDetectionDuration")));
            at = receivedAt.minusMillis(Math.max(0, Math.min(ms, 60_000)));
        } catch (NumberFormatException e) {
            // sem duração: instante de recebimento
        }
        return List.of(new ProviderCallEvent(callSid + ":amd", Type.ANSWERED, callSid, ref, at, amd, null, null, null,
                null, null));
    }

    private static List<ProviderCallEvent> recording(Map<String, List<String>> form, String callSid, UUID ref,
                                                     Instant at) {
        String status = lower(WebhookForms.first(form, "RecordingStatus"));
        String recordingSid = WebhookForms.first(form, "RecordingSid");
        if (!"completed".equals(status) || recordingSid == null || recordingSid.isBlank()) {
            return List.of();
        }
        Integer duration = null;
        try {
            duration = Integer.valueOf(safe(WebhookForms.first(form, "RecordingDuration")));
        } catch (NumberFormatException e) {
            // duração ausente
        }
        return List.of(new ProviderCallEvent(recordingSid + ":" + status, Type.RECORDING_AVAILABLE, callSid, ref, at,
                null, null, null, null, recordingSid, duration));
    }

    @Override
    public Optional<InstructionRequest> instructionRequest(WebhookRequest request) {
        String kind = request.query().getOrDefault(WebhookForms.KIND, "");
        UUID ref = WebhookForms.uuid(request.query(), WebhookForms.REFERENCE);
        return switch (kind) {
            case "inbound" -> Optional.of(new InstructionRequest(Kind.CALL_ANSWERED, callSid(request), null, null));
            case "announce" -> Optional.of(new InstructionRequest(Kind.ANNOUNCE, null, ref, null));
            default -> Optional.empty();
        };
    }

    @Override
    public WebhookReply render(WebhookRequest request, CallInstruction instruction) {
        String base = WebhookForms.stripQuery(request.url());
        String body = switch (instruction) {
            case CallInstruction.Announce a -> twiml.announce();
            case CallInstruction.ConnectAgent c -> twiml.connect(c.agentEndpoint(), c.callerId(), c.record(),
                    c.announceRecording(), WebhookForms.callback(base, Map.of(WebhookForms.KIND, "recording")));
            case CallInstruction.Hangup h -> h.abandonMessage() ? twiml.abandon() : twiml.hangup();
            case CallInstruction.Hold h -> twiml.dialerHold(h.announceRecording(), Math.max(20, h.holdSeconds()));
            case CallInstruction.DialDebtor d -> twiml.hangup();
            case CallInstruction.None n -> twiml.empty();
        };
        return new WebhookReply(TwilioTwiml.CONTENT_TYPE, body);
    }

    @Override
    public List<ConsoleEndpoint> consoleEndpoints() {
        return List.of(
                new ConsoleEndpoint("inbound", "Número → Voice → \"A call comes in\" (Webhook)", "POST", "?k=inbound"),
                new ConsoleEndpoint("inbound_status", "Número → Voice → \"Call status changes\"", "POST",
                        "?k=status&leg=inbound"));
    }

    private static String callSid(WebhookRequest request) {
        try {
            return WebhookForms.first(WebhookForms.parse(request.body()), "CallSid");
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** {@code Timestamp} dos eventos de andamento: UTC em RFC 2822 (ex.: {@code Mon, 16 Aug 2010 03:45:01 +0000}). */
    static Instant timestamp(String value, Instant fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (DateTimeParseException e) {
            return fallback;
        }
    }

    private static String lower(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String safe(String value) {
        return value == null ? "" : value.replaceAll("[^A-Za-z0-9]", "");
    }
}
