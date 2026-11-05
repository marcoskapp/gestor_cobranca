package br.com.gestorcobranca.collection.infrastructure.inbound;

import br.com.gestorcobranca.collection.application.InboundMailProperties;
import br.com.gestorcobranca.collection.application.port.out.InboundMailParser.ParsedMail;
import br.com.gestorcobranca.shared.json.JsonCodec;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class InboundMailAdaptersTest {

    private final JakartaInboundMailParser parser = new JakartaInboundMailParser();

    @Test
    void htmlBecomesPlainTextWithoutScriptsOrRemoteContent() {
        String raw = "From: \"Credor\" <Contato@BancoA.test>\r\nTo: contestacao+abc@respostas.test\r\n"
                + "Cc: juridico@bancoa.test\r\nSubject: Re: pedido\r\nMessage-ID: <m1@bancoa.test>\r\n"
                + "MIME-Version: 1.0\r\nContent-Type: text/html; charset=UTF-8\r\n\r\n"
                + "<html><body><p>Ol&aacute;,</p><p>Confirmamos.<br>Atenciosamente</p>"
                + "<script>steal()</script><style>p{}</style><img src=https://x.test/p.gif>"
                + "<a href=\"https://phish.test\">clique</a></body></html>";
        ParsedMail mail = parser.parse(raw.getBytes(StandardCharsets.UTF_8), 1000, 5);
        assertThat(mail.malformed()).isFalse();
        assertThat(mail.from()).isEqualTo("contato@bancoa.test");
        assertThat(mail.recipients()).contains("contestacao+abc@respostas.test", "juridico@bancoa.test");
        assertThat(mail.messageId()).isEqualTo("<m1@bancoa.test>");
        assertThat(mail.text()).contains("Olá,").contains("Confirmamos.\nAtenciosamente").contains("clique")
                .doesNotContain("steal").doesNotContain("p{}").doesNotContain("https://");
    }

    @Test
    void attachmentsAreCountedBeyondTheLimitAndTextIsTruncated() {
        StringBuilder raw = new StringBuilder("From: a@b.test\r\nSubject: x\r\nMIME-Version: 1.0\r\n"
                + "Content-Type: multipart/mixed; boundary=\"z\"\r\n\r\n--z\r\nContent-Type: text/plain\r\n\r\n"
                + "0123456789\r\n");
        for (int i = 0; i < 4; i++) {
            raw.append("--z\r\nContent-Type: application/pdf\r\nContent-Disposition: attachment; filename=\"../d").append(i)
                    .append(".pdf\"\r\n\r\n%PDF-1.4\r\n");
        }
        raw.append("--z--\r\n");
        ParsedMail mail = parser.parse(raw.toString().getBytes(StandardCharsets.US_ASCII), 5, 2);
        assertThat(mail.attachmentCount()).isEqualTo(4);
        assertThat(mail.attachments()).hasSizeLessThanOrEqualTo(3);
        assertThat(mail.attachments().getFirst().fileName()).doesNotContain("/");
        assertThat(mail.text()).isEqualTo("01234");
        assertThat(mail.textTruncated()).isTrue();
    }

    @Test
    void garbageIsMalformedNotAnException() {
        assertThat(parser.parse(new byte[]{0, 1, 2}, 10, 1).from()).isNull();
        assertThat(parser.parse("Content-Type: multipart/mixed; boundary=\"q\"\r\n\r\n--q\r\n".getBytes(
                StandardCharsets.US_ASCII), 10, 1)).isNotNull();
    }

    @Test
    void sesNotificationInsideSnsEnvelopeIsParsed() {
        String ses = """
                {"notificationType":"Received","mail":{"messageId":"abc123","timestamp":"2026-11-05T12:00:00.000Z"},
                 "receipt":{"recipients":["contestacao+x@respostas.test"],"spfVerdict":{"status":"PASS"},
                 "dkimVerdict":{"status":"PASS"},"dmarcVerdict":{"status":"GRAY"},"virusVerdict":{"status":"PASS"},
                 "spamVerdict":{"status":"PASS"},
                 "action":{"type":"S3","bucketName":"entrada","objectKey":"raw/abc123"}}}""";
        JsonCodec json = new JsonCodec(JsonMapper.builder().build());
        String envelope = json.toJson(java.util.Map.of("Type", "Notification", "Message", ses));
        InboundMailProperties props = new InboundMailProperties("ses", "respostas.test", null, null, null, null, null,
                null, new InboundMailProperties.Ses("https://sqs.test/q", "entrada", "sa-east-1", null), null);
        SesInboundMailConsumer consumer = new SesInboundMailConsumer(null, null, null, props, json,
                new SimpleMeterRegistry());
        SesInboundMailConsumer.Notification n = consumer.parse(envelope);
        assertThat(n.messageId()).isEqualTo("abc123");
        assertThat(n.recipients()).containsExactly("contestacao+x@respostas.test");
        assertThat(n.verdicts().dmarc()).isEqualTo("GRAY");
        assertThat(n.bucket()).isEqualTo("entrada");
        assertThat(n.objectKey()).isEqualTo("raw/abc123");
    }

    @Test
    void localAdapterSimulatesVerdictsFromHeaders() {
        var v = LocalDirectoryInboundMailSource.verdicts(("From: a@b.test\r\nX-Sandbox-Dmarc: FAIL\r\n"
                + "X-Sandbox-Virus: fail\r\n\r\nX-Sandbox-Spf: FAIL\r\n").getBytes(StandardCharsets.US_ASCII));
        assertThat(v.dmarc()).isEqualTo("FAIL");
        assertThat(v.virus()).isEqualTo("fail");
        assertThat(v.spf()).isEqualTo("PASS"); // só o bloco de cabeçalhos conta
    }
}
