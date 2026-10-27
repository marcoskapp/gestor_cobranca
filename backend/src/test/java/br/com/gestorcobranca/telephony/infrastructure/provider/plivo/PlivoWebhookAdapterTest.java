package br.com.gestorcobranca.telephony.infrastructure.provider.plivo;

import br.com.gestorcobranca.telephony.application.TelephonyProperties;
import br.com.gestorcobranca.telephony.application.port.out.CallInstruction;
import br.com.gestorcobranca.telephony.application.port.out.ProviderCallEvent;
import br.com.gestorcobranca.telephony.application.port.out.ProviderCallEvent.Type;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyWebhookAdapter.InstructionRequest.Kind;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyWebhookAdapter.WebhookRequest;
import br.com.gestorcobranca.telephony.infrastructure.provider.WebhookForms;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class PlivoWebhookAdapterTest {

    private static final String TOKEN = "plivoauthtoken0123456789";
    private static final String BASE = "https://gestor.teste/api/v1/webhooks/telephony/rk";
    private static final String CALL_UUID = "11111111-2222-4333-8444-555555555555";
    private static final String REQUEST_UUID = "99999999-8888-4777-8666-555555555555";
    private static final Instant NOW = Instant.parse("2026-10-26T15:00:00Z");

    private final TelephonyProperties properties = new TelephonyProperties(null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null);
    private final PlivoWebhookAdapter adapter = new PlivoWebhookAdapter(new PlivoXml(properties), properties);
    private final UUID ref = UUID.randomUUID();

    private WebhookRequest request(Map<String, String> query, Map<String, String> form) {
        String url = WebhookForms.callback(BASE, query);
        byte[] body = form.entrySet().stream().map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8)).collect(Collectors.joining("&"))
                .getBytes(StandardCharsets.UTF_8);
        Map<String, List<String>> params = new LinkedHashMap<>();
        form.forEach((k, v) -> params.put(k, List.of(v)));
        String nonce = UUID.randomUUID().toString();
        String signature = PlivoSignature.sign(TOKEN, PlivoSignature.baseString(url, params), nonce);
        return new WebhookRequest(url, query, Map.of(PlivoWebhookAdapter.SIGNATURE, signature, PlivoWebhookAdapter.NONCE,
                nonce), body, "application/x-www-form-urlencoded", NOW);
    }

    private static Map<String, String> map(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void signatureV3WithNonceIsRequired() {
        WebhookRequest valid = request(map("k", "ring", "ref", ref.toString(), "leg", "dialer"),
                map("CallUUID", CALL_UUID, "RequestUUID", REQUEST_UUID, "Event", "Ring", "CallStatus", "ringing"));
        assertThat(adapter.validSignature(Map.of("authToken", TOKEN), valid, NOW)).isTrue();
        assertThat(adapter.validSignature(Map.of("authToken", "outro0123456789abcdef"), valid, NOW)).isFalse();
        WebhookRequest otherNonce = new WebhookRequest(valid.url(), valid.query(),
                Map.of(PlivoWebhookAdapter.SIGNATURE, valid.headers().get(PlivoWebhookAdapter.SIGNATURE),
                        PlivoWebhookAdapter.NONCE, "outro"), valid.body(), null, NOW);
        assertThat(adapter.validSignature(Map.of("authToken", TOKEN), otherNonce, NOW)).isFalse();
    }

    @Test
    void dialerEventsBindTheCallUuidAndAwaitInstructions() {
        assertThat(adapter.parse(request(map("k", "ring", "ref", ref.toString(), "leg", "dialer"),
                map("CallUUID", CALL_UUID, "RequestUUID", REQUEST_UUID)))).singleElement().satisfies(e -> {
                    assertThat(e.type()).isEqualTo(Type.RINGING);
                    assertThat(e.providerCallId()).isEqualTo(REQUEST_UUID);
                    assertThat(e.providerLegId()).isEqualTo(CALL_UUID);
                    assertThat(e.callReference()).isEqualTo(ref);
                });
        // com AMD o atendimento espera o veredito; a resposta mantém na linha e volta a pedir instruções
        WebhookRequest answerAmd = request(map("k", "answer", "ref", ref.toString(), "leg", "dialer", "amd", "1"),
                map("CallUUID", CALL_UUID, "RequestUUID", REQUEST_UUID, "CallStatus", "in-progress"));
        assertThat(adapter.parse(answerAmd)).isEmpty();
        assertThat(adapter.instructionRequest(answerAmd)).hasValueSatisfying(r -> {
            assertThat(r.kind()).isEqualTo(Kind.CALL_ANSWERED);
            assertThat(r.providerCallId()).isEqualTo(REQUEST_UUID);
        });
        String hold = adapter.render(answerAmd, new CallInstruction.Hold(true, true, 0)).body();
        assertThat(hold).contains("<Record recordSession=\"true\"").contains("<Speak voice=\"WOMAN\" language=\"pt-BR\">")
                .contains("<Wait length=\"6\" silence=\"true\"/>").contains("k=amdwait");
        // sem AMD: atendimento e conexão na resposta
        WebhookRequest answer = request(map("k", "answer", "ref", ref.toString(), "leg", "dialer", "amd", "0"),
                map("CallUUID", CALL_UUID, "RequestUUID", REQUEST_UUID));
        assertThat(adapter.parse(answer)).singleElement().satisfies(e -> {
            assertThat(e.type()).isEqualTo(Type.ANSWERED);
            assertThat(e.awaitingInstructions()).isTrue();
        });
        String connect = adapter.render(answer, new CallInstruction.ConnectAgent("sip:opy123@phone.plivo.com",
                "+551130000000", true, true)).body();
        assertThat(connect).contains("<User>sip:opy123@phone.plivo.com</User>").contains("<Record recordSession")
                .contains("<Speak");
        // após a espera (amdwait) a gravação já existe: só a conexão
        WebhookRequest waited = request(map("k", "amdwait", "ref", ref.toString()), map("CallUUID", CALL_UUID,
                "RequestUUID", REQUEST_UUID));
        assertThat(adapter.parse(waited)).singleElement().satisfies(e -> {
            assertThat(e.amd()).isEqualTo("UNKNOWN");
            assertThat(e.awaitingInstructions()).isTrue();
        });
        assertThat(adapter.render(waited, new CallInstruction.ConnectAgent("+5511955554444", "+551130000000", true, true))
                .body()).doesNotContain("<Record").doesNotContain("<Speak").contains("<Number>5511955554444</Number>")
                .contains("callerId=\"551130000000\"");
        assertThat(adapter.parse(request(map("k", "amd", "ref", ref.toString()), map("CallUUID", CALL_UUID,
                "Machine", "true", "Event", "MachineDetection")))).singleElement().satisfies(e -> {
                    assertThat(e.amd()).isEqualTo("MACHINE");
                    assertThat(e.awaitingInstructions()).isFalse();
                });
    }

    @Test
    void hangupMapsAnswerAndCauses() {
        assertThat(adapter.parse(request(map("k", "hangup", "ref", ref.toString(), "leg", "dialer"), map("CallUUID",
                CALL_UUID, "RequestUUID", REQUEST_UUID, "AnswerTime", "2026-10-26 14:59:00", "HangupSource", "Callee"))))
                .singleElement().satisfies(e -> {
                    assertThat(e.type()).isEqualTo(Type.COMPLETED);
                    assertThat(e.hangupBy()).isEqualTo("DEBTOR");
                });
        assertThat(adapter.parse(request(map("k", "hangup", "ref", ref.toString(), "leg", "dialer"), map("CallUUID",
                CALL_UUID, "RequestUUID", REQUEST_UUID, "HangupCauseName", "Busy Line")))).singleElement()
                .extracting(ProviderCallEvent::type).isEqualTo(Type.BUSY);
        assertThat(adapter.parse(request(map("k", "hangup", "ref", ref.toString(), "leg", "dialer"), map("CallUUID",
                CALL_UUID, "RequestUUID", REQUEST_UUID)))).singleElement().extracting(ProviderCallEvent::type)
                .isEqualTo(Type.NO_ANSWER);
        assertThat(adapter.parse(request(map("k", "hangup", "ref", ref.toString(), "leg", "agent"), map("CallUUID",
                CALL_UUID, "RequestUUID", REQUEST_UUID)))).singleElement().satisfies(e ->
                assertThat(e.cause()).isEqualTo("AGENT_NO_ANSWER"));
    }

    @Test
    void clickToCallDialsTheDebtorFromTheResponseBodyOnly() {
        WebhookRequest agentAnswered = request(map("k", "answer", "ref", ref.toString(), "leg", "agent"),
                map("CallUUID", CALL_UUID, "RequestUUID", REQUEST_UUID));
        assertThat(adapter.parse(agentAnswered)).singleElement().satisfies(e -> {
            assertThat(e.type()).isEqualTo(Type.RINGING);
            assertThat(e.providerLegId()).isEqualTo(CALL_UUID);
        });
        assertThat(adapter.instructionRequest(agentAnswered)).hasValueSatisfying(r -> assertThat(r.kind())
                .isEqualTo(Kind.DIAL_DEBTOR));
        String xml = adapter.render(agentAnswered, new CallInstruction.DialDebtor("+5511987654321", "+551130000000", 30,
                true, true)).body();
        assertThat(xml).contains("<Number>5511987654321</Number>").contains("startOnDialAnswer=\"true\"")
                .contains("confirmSound=").contains("k=dialend").contains("k=dial&amp;").contains("redirect=\"false\"");
        // nenhuma URL carrega o número
        assertThat(xml.replace("<Number>5511987654321</Number>", "")).doesNotContain("98765");

        assertThat(adapter.parse(request(map("k", "dial", "ref", ref.toString()), map("DialAction", "answer",
                "DialBLegUUID", "b-1", "DialALegUUID", CALL_UUID)))).singleElement().satisfies(e -> {
                    assertThat(e.type()).isEqualTo(Type.ANSWERED);
                    assertThat(e.providerCallId()).isNull();
                    assertThat(e.callReference()).isEqualTo(ref);
                });
        assertThat(adapter.parse(request(map("k", "dialend", "ref", ref.toString()), map("DialStatus", "no-answer",
                "DialALegUUID", CALL_UUID)))).singleElement().extracting(ProviderCallEvent::type).isEqualTo(Type.NO_ANSWER);
    }

    @Test
    void inboundRecordingAndOtherInstructions() {
        WebhookRequest inbound = request(map("k", "inbound"), map("CallUUID", CALL_UUID, "From", "5511987654321",
                "Direction", "inbound"));
        assertThat(adapter.parse(inbound)).singleElement().satisfies(e -> {
            assertThat(e.type()).isEqualTo(Type.INBOUND);
            assertThat(e.providerCallId()).isEqualTo(CALL_UUID);
            assertThat(e.providerLegId()).isEqualTo(CALL_UUID);
            assertThat(e.awaitingInstructions()).isTrue();
        });
        assertThat(adapter.render(inbound, new CallInstruction.ConnectAgent("sip:opx1@phone.plivo.com", null, true, false))
                .body()).contains("pc=" + CALL_UUID);
        assertThat(adapter.render(inbound, new CallInstruction.Hangup(true)).body()).contains("<Speak").contains("<Hangup/>");

        assertThat(adapter.parse(request(map("k", "recording", "ref", ref.toString()), map("RecordingID", "rec-0001-abc",
                "RecordUrl", "https://media.plivo.com/x.mp3", "RecordingDuration", "42")))).singleElement().satisfies(e -> {
                    assertThat(e.type()).isEqualTo(Type.RECORDING_AVAILABLE);
                    assertThat(e.recordingRef()).isEqualTo("rec-0001-abc");
                });
        assertThat(adapter.parse(request(map("k", "recording", "ref", ref.toString()), map("RecordingID", "rec-0001-abc",
                "RecordingDuration", "-1")))).isEmpty();
        assertThat(adapter.instructionRequest(request(map("k", "bridge", "ref", ref.toString(), "op",
                UUID.randomUUID().toString()), map("CallUUID", CALL_UUID)))).hasValueSatisfying(r -> {
                    assertThat(r.kind()).isEqualTo(Kind.CONNECT_OPERATOR);
                    assertThat(r.operatorId()).isNotNull();
                });
        assertThat(adapter.instructionRequest(request(map("k", "abandon", "ref", ref.toString()), map("CallUUID",
                CALL_UUID)))).hasValueSatisfying(r -> assertThat(r.kind()).isEqualTo(Kind.ABANDON));
        assertThat(adapter.instructionRequest(request(map("k", "hangup"), map("CallUUID", CALL_UUID)))).isEmpty();
    }
}
