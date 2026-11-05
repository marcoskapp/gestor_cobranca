package br.com.gestorcobranca.collection;

import br.com.gestorcobranca.collection.application.port.in.DisputeMailReplyUseCases;
import br.com.gestorcobranca.collection.application.port.in.DisputeMailReplyUseCases.ConfirmCommand;
import br.com.gestorcobranca.collection.application.port.in.DisputeMailReplyUseCases.MailReplyView;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.CreditorRequestCommand;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.CreditorRequestDraft;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.DisputeQuery;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.DisputeView;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.OpenDisputeCommand;
import br.com.gestorcobranca.collection.application.port.in.InboundMailUseCases;
import br.com.gestorcobranca.collection.application.port.in.InboundMailUseCases.InboundMessage;
import br.com.gestorcobranca.collection.application.port.in.InboundMailUseCases.Outcome;
import br.com.gestorcobranca.collection.application.port.in.InboundMailUseCases.Verdicts;
import br.com.gestorcobranca.collection.application.port.out.CollectionPendingWorkQueries;
import br.com.gestorcobranca.collection.application.port.out.DisputeMailer;
import br.com.gestorcobranca.organization.application.port.in.CreditorDisputeContactUseCases;
import br.com.gestorcobranca.organization.application.port.in.CreditorDisputeContactUseCases.DisputeContactCommand;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.paging.PageQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import br.com.gestorcobranca.shared.security.ScopeAccessDeniedException;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Leitura das respostas do credor por e-mail contra o PostgreSQL real: endereço de resposta próprio do pedido (só o
 * hash na rota), triagem (remetente cadastrado, autenticação, vírus), texto sanitizado, original com SHA-256, anexos
 * pela quarentena com verificação de malware, confirmação pelo analista encerrando o "aguardando o credor", quarentena
 * decidida pela gestão, reentrega idempotente, e-mail sem rota registrado e conteúdo imutável no banco.
 */
@Import(DisputeMailReplyIT.MailConfig.class)
@TestPropertySource(properties = {
        "app.collection.inbound-mail.mode=local",
        "app.collection.inbound-mail.domain=respostas.teste.local",
        "app.collection.inbound-mail.local.directory=./target/inbound-mail-it",
        "app.collection.inbound-mail.local.poll-interval=PT1H",
        "app.collection.inbound-mail.per-sender-per-hour=3"})
class DisputeMailReplyIT extends CollectionTestSupport {

    private static final Pattern REPLY = Pattern.compile("contestacao\\+([a-z2-7]{26})@respostas\\.teste\\.local");
    private static final Verdicts PASS = new Verdicts("PASS", "PASS", "PASS", "PASS", "PASS");

    @TestConfiguration
    static class MailConfig {
        @Bean
        @Primary
        Recording recordingMailer() {
            return new Recording();
        }
    }

    static final class Recording implements DisputeMailer {

        final List<OutgoingMail> sent = new CopyOnWriteArrayList<>();

        @Override
        public Result send(OutgoingMail mail) {
            sent.add(mail);
            return Result.ok();
        }
    }

    @Autowired
    Recording mailer;
    @Autowired
    CreditorDisputeContactUseCases disputeContacts;
    @Autowired
    InboundMailUseCases inbound;
    @Autowired
    DisputeMailReplyUseCases mailReplies;
    @Autowired
    CollectionPendingWorkQueries pendingWork;

    @BeforeEach
    void setUp() {
        provision("mail");
        mailer.sent.clear();
        system(() -> disputeContacts.create(creditorA, new DisputeContactCommand(null, "Equipe de contestações",
                "contestacoes@bancoa.test", null, "PRIMARY")));
    }

    @Test
    void authenticReplyFromRegisteredContactIsConfirmedByAnalystAndClosesAwaitingCreditor() {
        DisputeView dispute = openDispute();
        String address = sendRequest(dispute);
        String token = token(address);
        // só o hash do token fica no banco (rota e pedido não guardam o token em claro)
        assertThat(count("select count(*) from collection.dispute_reply_route where token_hash = ?", token)).isZero();
        assertThat(count("select count(*) from collection.dispute_reply_route where token_hash = encode(sha256(?::bytea), 'hex')",
                token)).isEqualTo(1);
        assertThat(count("select count(*) from collection.dispute_creditor_request where reply_address_encrypted like ?",
                "%" + token + "%")).isZero();

        byte[] raw = mime("contestacoes@bancoa.test", address, "<p>Confirmamos a <b>baixa</b>.</p>"
                + "<script>alert('x')</script><img src=\"https://rastreio.test/p.gif\">", true);
        Outcome first = inbound.receive(message("ses-1", raw, address, PASS));
        assertThat(first).isEqualTo(Outcome.ACCEPTED);
        assertThat(inbound.receive(message("ses-1", raw, address, PASS))).isEqualTo(Outcome.DUPLICATE);

        List<MailReplyView> replies = asOperator(operatorX, () -> mailReplies.list(dispute.id()));
        assertThat(replies).hasSize(1);
        MailReplyView reply = replies.getFirst();
        assertThat(reply.status()).isEqualTo("PENDING_CONFIRMATION");
        assertThat(reply.authenticated()).isTrue();
        assertThat(reply.text()).contains("Confirmamos a baixa").doesNotContain("<script").doesNotContain("alert")
                .doesNotContain("rastreio.test");
        assertThat(reply.rawSha256()).hasSize(64);
        assertThat(reply.attachments()).isEqualTo(2);
        assertThat(reply.attachmentsRejected()).isEqualTo(1); // o .exe disfarçado não passa da lista de tipos
        assertThat(reply.attachmentsInVerification()).isEqualTo(1);

        // pendência do responsável e filtro da fila
        assertThat(system(() -> pendingWork.mailRepliesPending(null, operatorX)).total()).isEqualTo(1);
        var filtered = asOperator(operatorX, () -> disputes.search(new DisputeQuery(null, null, null, null, null, false,
                false, null, null, null, false, false, false, true), PageQuery.of(0, 20)));
        assertThat(filtered.content()).extracting(DisputeView::id).containsExactly(dispute.id());

        drainJobs(); // verificação de malware (desligada nos testes: NOT_SCANNED) libera o PDF como evidência do credor
        reply = asOperator(operatorX, () -> mailReplies.list(dispute.id())).getFirst();
        assertThat(reply.evidenceIds()).hasSize(1);
        assertThat(asOperator(operatorX, () -> disputes.listEvidence(dispute.id())))
                .anySatisfy(e -> assertThat(e.mailReplyId()).isEqualTo(replies.getFirst().id()));
        // ainda aguardando o credor: o sistema nunca resolve sozinho
        assertThat(asOperator(operatorX, () -> disputes.get(dispute.id())).awaitingCreditorSince()).isNotNull();

        UUID replyId = reply.id();
        var response = asOperator(operatorX, () -> mailReplies.confirm(dispute.id(), replyId,
                new ConfirmCommand("ACCEPTS_CLAIM", "Credor confirma a baixa do título.")));
        assertThat(response.source()).isEqualTo("EMAIL");
        assertThat(asOperator(operatorX, () -> disputes.get(dispute.id())).awaitingCreditorSince()).isNull();
        assertThat(asOperator(operatorX, () -> disputes.creditorRequests(dispute.id())).getFirst().status())
                .isEqualTo("ANSWERED");
        MailReplyView confirmed = asOperator(operatorX, () -> mailReplies.list(dispute.id())).getFirst();
        assertThat(confirmed.status()).isEqualTo("CONFIRMED");
        assertThat(confirmed.responseId()).isEqualTo(response.id());
        assertThatThrownBy(() -> asOperator(operatorX, () -> mailReplies.confirm(dispute.id(), replyId,
                new ConfirmCommand("ACCEPTS_CLAIM", "de novo")))).isInstanceOf(ConflictException.class);
        // conteúdo e desfecho imutáveis no banco
        assertThatThrownBy(() -> system(() -> jdbcTemplate.update(
                "update collection.dispute_mail_reply set decision_note_encrypted = 'x' where id = ?", replyId)))
                .rootCause().hasMessageContaining("já decidido");
        assertThatThrownBy(() -> system(() -> jdbcTemplate.update(
                "delete from collection.dispute_mail_reply where id = ?", replyId)))
                .rootCause().hasMessageContaining("append-only");
        assertThat(count("select count(*) from shared.audit_event where action = 'dispute.mail_confirmed'"))
                .isEqualTo(1);

        // resposta seguinte no mesmo fio entra no histórico, de novo para confirmar
        assertThat(inbound.receive(message("ses-2", mime("contestacoes@bancoa.test", address, "Segue complemento.",
                false), address, PASS))).isEqualTo(Outcome.ACCEPTED);
        assertThat(asOperator(operatorX, () -> mailReplies.list(dispute.id()))).hasSize(2);
    }

    @Test
    void suspiciousRepliesGoToQuarantineDecidedByManagementAndNeverSilentlyDropped() {
        DisputeView dispute = openDispute();
        String address = sendRequest(dispute);

        // remetente que não é contato de contestação do credor
        assertThat(inbound.receive(message("ses-10", mime("alguem@golpe.test", address, "Pague neste boleto.", false),
                address, PASS))).isEqualTo(Outcome.QUARANTINED);
        // DMARC reprovado (possível falsificação do contato)
        assertThat(inbound.receive(message("ses-11", mime("contestacoes@bancoa.test", address, "Texto.", false), address,
                new Verdicts("PASS", "FAIL", "FAIL", "PASS", "PASS")))).isEqualTo(Outcome.QUARANTINED);
        // vírus apontado pelo SES
        assertThat(inbound.receive(message("ses-12", mime("contestacoes@bancoa.test", address, "Anexo.", true), address,
                new Verdicts("PASS", "PASS", "PASS", "FAIL", "PASS")))).isEqualTo(Outcome.QUARANTINED);

        List<MailReplyView> replies = asOperator(operatorX, () -> mailReplies.list(dispute.id()));
        assertThat(replies).extracting(MailReplyView::quarantineReason)
                .containsExactly("SENDER_NOT_REGISTERED", "AUTHENTICATION_FAILED", "VIRUS");
        assertThat(replies).allSatisfy(r -> {
            assertThat(r.status()).isEqualTo("QUARANTINED");
            assertThat(r.attachmentsInVerification()).isZero(); // anexos da quarentena não são processados
        });
        assertThat(count("select count(*) from shared.access_log where event_type = 'INBOUND_MAIL_REJECTED'"))
                .isEqualTo(3);
        assertThat(system(() -> pendingWork.mailRepliesQuarantined(null)).total()).isEqualTo(3);
        assertThat(asSupervisor(() -> mailReplies.quarantined(PageQuery.of(0, 20))).content()).hasSize(3);

        MailReplyView unknownSender = replies.get(0);
        MailReplyView spoofed = replies.get(1);
        // quem trabalha a contestação não decide a quarentena
        assertThatThrownBy(() -> asOperator(operatorX, () -> mailReplies.release(dispute.id(), unknownSender.id(),
                "ok"))).isInstanceOf(ScopeAccessDeniedException.class);
        // falha de autenticação nunca é liberada; só descartada com motivo
        assertThatThrownBy(() -> asSupervisor(() -> mailReplies.release(dispute.id(), spoofed.id(), "parece ok")))
                .isInstanceOf(ConflictException.class);
        asSupervisor(() -> mailReplies.dismiss(dispute.id(), spoofed.id(), "DMARC reprovado: não é o credor"));

        MailReplyView released = asSupervisor(() -> mailReplies.release(dispute.id(), unknownSender.id(),
                "Novo contato do credor, confirmado por telefone"));
        assertThat(released.status()).isEqualTo("PENDING_CONFIRMATION");
        assertThat(released.releasedByName()).isNotNull();
        assertThat(system(() -> pendingWork.mailRepliesQuarantined(null)).total()).isEqualTo(1);

        // excesso do remetente (limite de teste: 3 por hora)
        inbound.receive(message("ses-13", mime("contestacoes@bancoa.test", address, "1", false), address, PASS));
        assertThat(inbound.receive(message("ses-14", mime("contestacoes@bancoa.test", address, "2", false), address,
                PASS))).isEqualTo(Outcome.QUARANTINED);
        assertThat(asOperator(operatorX, () -> mailReplies.list(dispute.id())).getLast().quarantineReason())
                .isEqualTo("RATE_LIMITED");
    }

    @Test
    void mailWithoutMatchingRequestIsRecordedAsUnrouted() {
        String unknown = "contestacao+aaaaaaaaaaaaaaaaaaaaaaaaaa@respostas.teste.local";
        assertThat(inbound.receive(message("ses-20", mime("x@y.test", unknown, "oi", false), unknown, PASS)))
                .isEqualTo(Outcome.UNROUTED);
        assertThat(inbound.receive(message("ses-20", mime("x@y.test", unknown, "oi", false), unknown, PASS)))
                .isEqualTo(Outcome.DUPLICATE);
        assertThat(inbound.receive(message("ses-21", mime("x@y.test", "outro@respostas.teste.local", "oi", false),
                "outro@respostas.teste.local", PASS))).isEqualTo(Outcome.UNROUTED);
        assertThat(count("select count(*) from collection.inbound_mail_unrouted where provider_message_id in ('ses-20', 'ses-21')"))
                .isEqualTo(2);
    }

    // ------------------------------------------------------------------ apoio

    private DisputeView openDispute() {
        UUID debtor = debtor("52998224725");
        load(creditorA, portfolioA, debtor, "300.00");
        UUID caseId = activeCase(debtor, portfolioA).id();
        DisputeView d = asOperator(operatorX, () -> disputes.open(caseId, new OpenDisputeCommand(Set.of(), "ALREADY_PAID",
                "DEBTOR", "Contestação de teste", null, null)));
        asSupervisor(() -> disputes.assign(d.id(), operatorX, "Analista"));
        return d;
    }

    /** Envia o pedido e devolve o endereço de resposta usado no e-mail. */
    private String sendRequest(DisputeView dispute) {
        CreditorRequestDraft draft = asOperator(operatorX, () -> disputes.draftCreditorRequest(dispute.id()));
        var request = asOperator(operatorX, () -> disputes.requestCreditor(dispute.id(),
                new CreditorRequestCommand(draft.subject(), draft.body(), null, Set.of(), Set.of(), false)));
        // com a leitura por e-mail ligada, o responsável vai em cópia mesmo sem marcar a opção
        assertThat(request.cc()).contains(draft.replyTo());
        drainJobs();
        DisputeMailer.OutgoingMail mail = mailer.sent.getLast();
        assertThat(mail.replyTo()).matches(REPLY.pattern());
        assertThat(mail.text()).contains("Responda a este e-mail");
        return mail.replyTo();
    }

    private static String token(String address) {
        Matcher m = REPLY.matcher(address);
        assertThat(m.matches()).isTrue();
        return m.group(1);
    }

    private static InboundMessage message(String id, byte[] raw, String recipient, Verdicts verdicts) {
        return new InboundMessage(id, raw, List.of(recipient), verdicts, Instant.now(), "s3://teste/raw/" + id);
    }

    /** E-mail MIME com corpo HTML ou texto e, opcionalmente, um PDF válido e um executável disfarçado de PDF. */
    private static byte[] mime(String from, String to, String body, boolean attachments) {
        boolean html = body.contains("<");
        StringBuilder s = new StringBuilder()
                .append("From: Credor <").append(from).append(">\r\n")
                .append("To: ").append(to).append("\r\n")
                .append("Subject: =?UTF-8?Q?Re:_Contesta=C3=A7=C3=A3o?=\r\n")
                .append("Message-ID: <").append(UUID.randomUUID()).append("@bancoa.test>\r\n")
                .append("MIME-Version: 1.0\r\n")
                .append("Content-Type: multipart/mixed; boundary=\"b1\"\r\n\r\n")
                .append("--b1\r\nContent-Type: ").append(html ? "text/html" : "text/plain")
                .append("; charset=UTF-8\r\n\r\n").append(body).append("\r\n");
        if (attachments) {
            s.append("--b1\r\nContent-Type: application/pdf; name=\"baixa.pdf\"\r\n")
                    .append("Content-Disposition: attachment; filename=\"baixa.pdf\"\r\n")
                    .append("Content-Transfer-Encoding: base64\r\n\r\n")
                    .append(Base64.getMimeEncoder().encodeToString("%PDF-1.4\n%%EOF\n".getBytes(StandardCharsets.US_ASCII)))
                    .append("\r\n--b1\r\nContent-Type: application/pdf; name=\"fatura.pdf\"\r\n")
                    .append("Content-Disposition: attachment; filename=\"fatura.pdf\"\r\n")
                    .append("Content-Transfer-Encoding: base64\r\n\r\n")
                    .append(Base64.getMimeEncoder().encodeToString("MZ\u0090\u0000executavel".getBytes(StandardCharsets.ISO_8859_1)))
                    .append("\r\n");
        }
        s.append("--b1--\r\n");
        return s.toString().getBytes(StandardCharsets.UTF_8);
    }
}
