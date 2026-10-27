package br.com.gestorcobranca.telephony.infrastructure.provider.plivo;

import br.com.gestorcobranca.telephony.application.TelephonyProperties;
import br.com.gestorcobranca.telephony.application.port.out.CallInstruction;
import br.com.gestorcobranca.telephony.application.port.out.ProviderCallEvent;
import br.com.gestorcobranca.telephony.application.port.out.ProviderCallEvent.Type;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyWebhookAdapter;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyWebhookAdapter.InstructionRequest.Kind;
import br.com.gestorcobranca.telephony.domain.model.ProviderType;
import br.com.gestorcobranca.telephony.infrastructure.provider.WebhookForms;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Webhooks da Plivo (anexo D do desenho). Autenticidade por {@code X-Plivo-Signature-V3} com
 * {@code X-Plivo-Signature-V3-Nonce} ({@link PlivoSignature}). A Plivo não assina instante: replay é
 * contido pelo recibo do corpo e pela idempotência por evento ({@code CallUUID} + passo).
 * <p>
 * Tipo do callback pela consulta {@code k} das URLs geradas pelo adaptador:
 * <ul>
 *   <li>{@code answer} — atendimento: perna do operador ({@code leg=agent}) → toque do devedor e
 *       instruções para discar; discador sem AMD → {@code ANSWERED} e conexão na resposta; discador com
 *       AMD → espera o veredito;</li>
 *   <li>{@code ring} → {@code RINGING}; {@code hangup} → fim conforme o atendimento e a causa;</li>
 *   <li>{@code amd} — {@code Machine=true} → máquina; {@code false} → pessoa;</li>
 *   <li>{@code amdwait} — espera esgotada sem veredito de máquina → atendida (desconhecido, tratado como
 *       pessoa) e conexão na resposta;</li>
 *   <li>{@code dial} ({@code callbackUrl} do {@code <Dial>} ao devedor) — {@code answer} →
 *       {@code ANSWERED}, {@code hangup} → {@code COMPLETED}; {@code dialend} ({@code action}) — fim sem
 *       atendimento ({@code DialStatus});</li>
 *   <li>{@code recording} — arquivo pronto ({@code RecordingID});</li>
 *   <li>{@code inbound} — {@code answer_url} da aplicação do número (chamada recebida);</li>
 *   <li>{@code announce}, {@code bridge}, {@code abandon} — só instruções.</li>
 * </ul>
 */
@Component
class PlivoWebhookAdapter implements TelephonyWebhookAdapter {

    static final String SIGNATURE = "x-plivo-signature-v3";
    static final String NONCE = "x-plivo-signature-v3-nonce";

    private final PlivoXml xml;
    private final int amdWaitSeconds;

    PlivoWebhookAdapter(PlivoXml xml, TelephonyProperties properties) {
        this.xml = xml;
        this.amdWaitSeconds = (int) Math.ceil(Math.max(2000, Math.min(10_000, properties.plivo().amdTime().toMillis()))
                / 1000.0) + 1;
    }

    @Override
    public ProviderType provider() {
        return ProviderType.PLIVO;
    }

    @Override
    public boolean validSignature(Map<String, String> credentials, WebhookRequest request, Instant now) {
        Map<String, List<String>> form;
        try {
            form = WebhookForms.parse(request.body());
        } catch (IllegalArgumentException e) {
            return false;
        }
        return PlivoSignature.valid(credentials.get("authToken"), request.url(), form, request.headers().get(NONCE),
                request.headers().get(SIGNATURE));
    }

    @Override
    public List<ProviderCallEvent> parse(WebhookRequest request) {
        Map<String, List<String>> form = WebhookForms.parse(request.body());
        Map<String, String> q = request.query();
        String kind = q.getOrDefault(WebhookForms.KIND, "");
        String leg = q.getOrDefault(WebhookForms.LEG, "");
        UUID ref = WebhookForms.uuid(q, WebhookForms.REFERENCE);
        Instant at = request.receivedAt();
        String callUuid = WebhookForms.first(form, "CallUUID");
        String requestUuid = WebhookForms.first(form, "RequestUUID");
        return switch (kind) {
            case "answer" -> {
                if ("agent".equals(leg)) {
                    // operador atendeu: o devedor passa a tocar (instruções na resposta)
                    yield List.of(event(callUuid + ":answer", Type.RINGING, requestUuid, ref, at, null, null, callUuid,
                            false));
                }
                if ("1".equals(q.get(WebhookForms.AMD))) {
                    yield List.of();
                }
                yield List.of(event(callUuid + ":answer", Type.ANSWERED, requestUuid, ref, at, null, null, callUuid, true));
            }
            case "ring" -> List.of(event(callUuid + ":ring", Type.RINGING, requestUuid, ref, at, null, null, callUuid,
                    false));
            case "amd" -> List.of(event(callUuid + ":amd", Type.ANSWERED, requestUuid, ref, at,
                    "true".equalsIgnoreCase(WebhookForms.first(form, "Machine")) ? "MACHINE" : "HUMAN", null, callUuid,
                    false));
            case "amdwait" -> List.of(event(callUuid + ":amdwait", Type.ANSWERED, requestUuid, ref, at, "UNKNOWN", null,
                    callUuid, true));
            case "hangup" -> hangup(form, leg, ref, at, callUuid, requestUuid);
            case "dial" -> dial(form, ref, at);
            case "dialend" -> dialEnd(form, ref, at);
            case "recording" -> recording(form, q, ref, at);
            case "inbound" -> List.of(event(callUuid + ":inbound", Type.INBOUND, callUuid, null, at, null,
                    WebhookForms.first(form, "From"), callUuid, true));
            default -> List.of();
        };
    }

    private static List<ProviderCallEvent> hangup(Map<String, List<String>> form, String leg, UUID ref, Instant at,
                                                  String callUuid, String requestUuid) {
        boolean answered = present(WebhookForms.first(form, "AnswerTime"));
        String cause = lower(firstOf(form, "HangupCauseName", "HangupCause"));
        String status = lower(WebhookForms.first(form, "CallStatus"));
        Type type;
        if (answered && !"agent".equals(leg)) {
            type = Type.COMPLETED;
        } else if (answered) {
            // perna do operador atendida: o fim da conversa já veio pelo <Dial>; fim tardio é obsoleto
            type = Type.COMPLETED;
        } else if (status.contains("busy") || cause.contains("busy")) {
            type = Type.BUSY;
        } else if (status.contains("fail") || cause.contains("invalid") || cause.contains("unallocated")
                || cause.contains("fail")) {
            type = Type.FAILED;
        } else if (status.contains("cancel") || cause.contains("cancel")) {
            type = Type.CANCELLED;
        } else {
            type = Type.NO_ANSWER;
        }
        String code = cause.isBlank() ? null : "PLIVO_" + cause.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
        if ("agent".equals(leg) && !answered) {
            code = "AGENT_" + type.name();
        }
        String hangupBy = hangupSide(lower(WebhookForms.first(form, "HangupSource")), leg);
        String providerCallId = "inbound".equals(leg) ? callUuid : requestUuid;
        return List.of(event(callUuid + ":hangup", type, providerCallId, ref, at, null, hangupBy, code, null, callUuid,
                false));
    }

    private static List<ProviderCallEvent> dial(Map<String, List<String>> form, UUID ref, Instant at) {
        String action = lower(WebhookForms.first(form, "DialAction"));
        String bLeg = WebhookForms.first(form, "DialBLegUUID");
        Type type = switch (action) {
            case "answer" -> Type.ANSWERED;
            case "hangup" -> Type.COMPLETED;
            default -> null;
        };
        if (type == null || ref == null) {
            return List.of();
        }
        String hangupBy = type == Type.COMPLETED
                ? hangupSide(lower(WebhookForms.first(form, "DialBLegHangupSource")), "customer") : null;
        return List.of(new ProviderCallEvent((bLeg == null ? ref : bLeg) + ":dial:" + action, type, null, ref, at, null,
                hangupBy, null, null, null, null));
    }

    private static List<ProviderCallEvent> dialEnd(Map<String, List<String>> form, UUID ref, Instant at) {
        String status = lower(WebhookForms.first(form, "DialStatus"));
        Type type = switch (status) {
            case "completed" -> Type.COMPLETED;
            case "busy" -> Type.BUSY;
            case "failed" -> Type.FAILED;
            case "cancel" -> Type.CANCELLED;
            case "timeout", "no-answer" -> Type.NO_ANSWER;
            default -> null;
        };
        if (type == null || ref == null) {
            return List.of();
        }
        String aLeg = WebhookForms.first(form, "DialALegUUID");
        return List.of(new ProviderCallEvent((aLeg == null ? ref : aLeg) + ":dialend:" + status, type, null, ref, at, null,
                null, type == Type.FAILED ? "PLIVO_DIAL_FAILED" : null, null, null, null));
    }

    private static List<ProviderCallEvent> recording(Map<String, List<String>> form, Map<String, String> q, UUID ref,
                                                     Instant at) {
        String id = WebhookForms.first(form, "RecordingID");
        if (id == null || id.isBlank()) {
            return List.of();
        }
        Integer duration = null;
        try {
            duration = Integer.valueOf(WebhookForms.first(form, "RecordingDuration"));
        } catch (NumberFormatException e) {
            // duração ausente
        }
        if (duration != null && duration < 0) {
            // aviso de início da gravação de sessão (duração -1): o arquivo ainda não existe
            return List.of();
        }
        return List.of(new ProviderCallEvent(id + ":recording", Type.RECORDING_AVAILABLE, q.get("pc"), ref, at, null,
                null, null, null, id, duration));
    }

    @Override
    public Optional<InstructionRequest> instructionRequest(WebhookRequest request) {
        Map<String, String> q = request.query();
        String kind = q.getOrDefault(WebhookForms.KIND, "");
        UUID ref = WebhookForms.uuid(q, WebhookForms.REFERENCE);
        String requestUuid = formValue(request, "RequestUUID");
        return switch (kind) {
            case "answer" -> Optional.of("agent".equals(q.get(WebhookForms.LEG))
                    ? new InstructionRequest(Kind.DIAL_DEBTOR, requestUuid, ref, null)
                    : new InstructionRequest(Kind.CALL_ANSWERED, requestUuid, ref, null));
            case "amdwait" -> Optional.of(new InstructionRequest(Kind.CALL_ANSWERED, requestUuid, ref, null));
            case "inbound" -> Optional.of(new InstructionRequest(Kind.CALL_ANSWERED, formValue(request, "CallUUID"), null,
                    null));
            case "announce" -> Optional.of(new InstructionRequest(Kind.ANNOUNCE, null, ref, null));
            case "bridge" -> Optional.of(new InstructionRequest(Kind.CONNECT_OPERATOR, null, ref,
                    WebhookForms.uuid(q, WebhookForms.OPERATOR)));
            case "abandon" -> Optional.of(new InstructionRequest(Kind.ABANDON, null, ref, null));
            case "dialend" -> Optional.of(new InstructionRequest(Kind.END, null, ref, null));
            default -> Optional.empty();
        };
    }

    @Override
    public WebhookReply render(WebhookRequest request, CallInstruction instruction) {
        Map<String, String> q = request.query();
        String kind = q.getOrDefault(WebhookForms.KIND, "");
        String ref = q.get(WebhookForms.REFERENCE);
        String base = WebhookForms.stripQuery(request.url());
        // primeiro documento da chamada: a gravação e o aviso começam aqui; nos passos seguintes já estão ativos
        boolean firstDocument = "answer".equals(kind) || "inbound".equals(kind);
        String callUuid = formValue(request, "CallUUID");
        String body = switch (instruction) {
            case CallInstruction.DialDebtor d -> xml.dialDebtor(d.destination(), d.callerId(), d.ringTimeoutSeconds(),
                    d.record() ? callback(base, "recording", ref, null) : null, callback(base, "dial", ref, null),
                    callback(base, "dialend", ref, null), d.announceRecording() ? callback(base, "announce", ref, null) : null);
            case CallInstruction.Hold h -> "answer".equals(kind)
                    ? xml.hold(h.record() ? callback(base, "recording", ref, null) : null, h.announceRecording(),
                    amdWaitSeconds, callback(base, "amdwait", ref, null))
                    : xml.abandon();
            case CallInstruction.ConnectAgent c -> xml.connect(c.agentEndpoint(), c.callerId(),
                    firstDocument && c.record() ? callback(base, "recording", ref, "inbound".equals(kind) ? callUuid : null)
                            : null,
                    firstDocument && c.announceRecording());
            case CallInstruction.Announce a -> xml.announce();
            case CallInstruction.Hangup h -> h.abandonMessage() ? xml.abandon() : xml.hangup();
            case CallInstruction.None n -> xml.empty();
        };
        return new WebhookReply(PlivoXml.CONTENT_TYPE, body);
    }

    @Override
    public List<ConsoleEndpoint> consoleEndpoints() {
        return List.of(
                new ConsoleEndpoint("inbound", "Application → Answer URL (chamadas recebidas)", "POST", "?k=inbound"),
                new ConsoleEndpoint("inbound_hangup", "Application → Hangup URL", "POST", "?k=hangup&leg=inbound"));
    }

    private static String callback(String base, String kind, String ref, String providerCallId) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put(WebhookForms.KIND, kind);
        query.put(WebhookForms.REFERENCE, ref);
        query.put("pc", providerCallId);
        return WebhookForms.callback(base, query);
    }

    private static ProviderCallEvent event(String id, Type type, String providerCallId, UUID ref, Instant at, String amd,
                                           String from, String legId, boolean awaiting) {
        return new ProviderCallEvent(id, type, providerCallId, ref, at, amd, null, null, from, null, null, legId, awaiting);
    }

    private static ProviderCallEvent event(String id, Type type, String providerCallId, UUID ref, Instant at, String amd,
                                           String hangupBy, String cause, String from, String legId, boolean awaiting) {
        return new ProviderCallEvent(id, type, providerCallId, ref, at, amd, hangupBy, cause, from, null, null, legId,
                awaiting);
    }

    private static String hangupSide(String source, String leg) {
        if (source.isBlank()) {
            return null;
        }
        if (source.contains("api") || source.contains("plivo")) {
            return "SYSTEM";
        }
        if (source.contains("callee")) {
            return "agent".equals(leg) ? "OPERATOR" : "DEBTOR";
        }
        if (source.contains("caller")) {
            return "customer".equals(leg) ? "OPERATOR" : null;
        }
        return null;
    }

    private static String formValue(WebhookRequest request, String name) {
        try {
            return WebhookForms.first(WebhookForms.parse(request.body()), name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String firstOf(Map<String, List<String>> form, String... names) {
        for (String n : names) {
            String v = WebhookForms.first(form, n);
            if (present(v)) {
                return v;
            }
        }
        return null;
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static String lower(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
