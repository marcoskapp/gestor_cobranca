package br.com.gestorcobranca.portal;

import br.com.gestorcobranca.communication.application.port.in.MessageUseCases;
import br.com.gestorcobranca.communication.application.port.in.TemplateUseCases.VariableInput;
import br.com.gestorcobranca.compliance.application.port.in.ContactPolicyUseCases.ChannelRuleInput;
import br.com.gestorcobranca.compliance.application.port.in.ContactPolicyUseCases.PublishPolicyCommand;
import br.com.gestorcobranca.portal.application.port.in.PortalOperationsUseCases.AccessLinkView;
import br.com.gestorcobranca.shared.security.JwtProperties;
import br.com.gestorcobranca.shared.security.TokenClaims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import java.net.http.HttpRequest;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Acesso seguro do devedor contra PostgreSQL real e pela cadeia HTTP: documento + código, link de uso
 * único, desafio de conhecimento, anti-enumeração, bloqueio progressivo, separação das sessões do portal
 * e da API interna, revogação e log de acesso {@code PORTAL_*}.
 */
class PortalAccessIT extends PortalTestSupport {

    @Autowired
    JwtEncoder jwtEncoder;
    @Autowired
    JwtProperties jwtProperties;
    @Autowired
    org.springframework.context.ApplicationEventPublisher publisher;

    @BeforeEach
    void setUp() {
        provision("pacc");
    }

    @Test
    void documentAndCodeOpenASessionScopedToTheDebtor() {
        Response site = get("/api/portal/v1/sites/" + slug, null);
        assertThat(site.status()).isEqualTo(200);
        assertThat(site.json().get("displayName").asString()).isEqualTo("Banco A");
        assertThat(site.json().get("otpChannels").get(0).asString()).isEqualTo("WHATSAPP");
        assertThat(site.json().get("privacyNoticeVersion").asInt()).isEqualTo(1);

        UUID challenge = requestCode(CPF_MARIA);
        String code = sentCode(challenge);
        // o código não aparece em tela nenhuma: a mensagem mostra só que foi enviado (decisão 1084)
        UUID messageId = system(() -> jdbcTemplate.queryForObject(
                "select message_id from portal.access_challenge where id = ?", UUID.class, challenge));
        assertThat(asAdmin(() -> messages.get(messageId)).content())
                .isEqualTo("Código de acesso ao portal enviado (oculto por segurança).").doesNotContain(code);
        Response wrong = verify(challenge, code.equals("000000") ? "111111" : "000000", null);
        assertThat(wrong.status()).isEqualTo(401);
        assertThat(wrong.json().get("code").asString()).isEqualTo("portal.access_invalid");

        Response ok = verify(challenge, code, null);
        assertThat(ok.status()).isEqualTo(200);
        String token = ok.json().get("accessToken").asString();
        // código é de uso único
        assertThat(verify(challenge, code, null).status()).isEqualTo(401);

        Response session = get("/api/portal/v1/me/session", token);
        assertThat(session.status()).isEqualTo(200);
        assertThat(session.json().get("firstName").asString()).isEqualTo("Maria");
        assertThat(session.json().get("privacyAcknowledged").asBoolean()).isFalse();
        // o banco nunca guarda o código em claro
        assertThat(count("select count(*) from portal.access_challenge where code_hash = ?", code)).isZero();
        assertThat(count("select count(*) from shared.access_log where event_type = 'PORTAL_LOGIN_SUCCESS' and actor_id = ?",
                maria)).isEqualTo(1);
        assertThat(count("select count(*) from shared.business_event where event_type = 'portal.accessed' and debtor_id = ?",
                maria)).isEqualTo(1);
    }

    @Test
    void codeRequestedAgainRightAwayStillArrivesDespiteTheContactPolicyLimits() {
        // política do credor: 1 WhatsApp por dia e 2 h entre contatos — vale para cobrança, não para o código pedido
        // (autenticação: também fora da janela de horário e dos feriados, decisão 1083)
        system(() -> contactPolicies.publish(new PublishPolicyCommand(creditorA, null, "limites", List.of(
                new ChannelRuleInput("WHATSAPP", LocalTime.MIDNIGHT, LocalTime.MIDNIGHT, ALL_DAYS, 1, null, 120, false)))));
        UUID first = requestCode(CPF_MARIA);
        assertThat(verify(first, sentCode(first), null).status()).isEqualTo(200);
        // saiu do portal e pediu outro código logo em seguida: o código chega e entra
        UUID second = requestCode(CPF_MARIA);
        assertThat(verify(second, sentCode(second), null).status()).isEqualTo(200);
        drainJobs("communication.");
        assertThat(count("select count(*) from compliance.contact_attempt where debtor_id = ? and category = 'AUTHENTICATION'",
                maria)).isEqualTo(2);
        assertThat(count("select count(*) from compliance.contact_attempt where debtor_id = ? and category = 'COLLECTION'",
                maria)).isZero();
    }

    @Test
    void dailyCodeCapPerDocumentLocksUntilMidnightLikeAnyOtherDocument() {
        UUID first = requestCode(CPF_MARIA);
        sentCode(first);
        // mais 13 códigos do mesmo documento hoje (14 no total): o 15º ainda sai
        copyChallenge(first, 13);
        UUID fifteenth = requestCode(CPF_MARIA);
        assertThat(count("select count(*) from portal.access_challenge where id = ? and debtor_id is not null",
                fifteenth)).isEqualTo(1);
        // com 15 no dia, chega ao teto (decisão 1083)
        Response capped = post("/api/portal/v1/sites/" + slug + "/access/code",
                Map.of("document", CPF_MARIA, "channel", "WHATSAPP"), null);
        // bloqueio visível até a meia-noite (decisão 1090), sem desafio novo e sem mensagem
        assertThat(capped.status()).isEqualTo(429);
        assertThat(capped.json().get("retryAfterSeconds").asLong()).isPositive().isLessThanOrEqualTo(86_400);
        assertThat(capped.json().get("reason").asText()).isEqualTo("DAILY_CODE_CAP");
        assertThat(count("select count(*) from portal.access_challenge where tenant_id = ?", tenant)).isEqualTo(15);
        assertThat(count("select count(*) from shared.access_log where event_type = 'PORTAL_CODE_REQUESTED' "
                + "and details ->> 'dailyCap' = 'true' and details ->> 'match' = 'true'")).isEqualTo(1);
        // documento sem cadastro no teto recebe o mesmo bloqueio (não revela quem é devedor)
        UUID decoy = requestCode(CPF_UNKNOWN);
        copyChallenge(decoy, 14);
        Response unknown = post("/api/portal/v1/sites/" + slug + "/access/code",
                Map.of("document", CPF_UNKNOWN, "channel", "WHATSAPP"), null);
        assertThat(unknown.status()).isEqualTo(429);
        assertThat(unknown.json().get("reason").asText()).isEqualTo("DAILY_CODE_CAP");
        // no teto, novos pedidos não somam no bloqueio progressivo: a resposta segue sendo o teto, não "15 minutos"
        Response again = post("/api/portal/v1/sites/" + slug + "/access/code",
                Map.of("document", CPF_UNKNOWN, "channel", "WHATSAPP"), null);
        assertThat(again.json().get("reason").asText()).isEqualTo("DAILY_CODE_CAP");
    }

    /** Cópias do desafio (mesmo documento, mesmo dia), para chegar ao teto diário sem esbarrar no limite por janela. */
    private void copyChallenge(UUID challenge, int copies) {
        system(() -> jdbcTemplate.update("""
                insert into portal.access_challenge (id, tenant_id, version, created_at, created_by, updated_at, updated_by,
                       configuration_id, debtor_id, method, channel, document_hash, code_hash, link_id, message_id,
                       knowledge_challenge, attempts, max_attempts, status, expires_at, completed_at)
                select gen_random_uuid(), tenant_id, version, created_at, created_by, updated_at, updated_by,
                       configuration_id, debtor_id, method, channel, document_hash, code_hash, link_id, null,
                       knowledge_challenge, attempts, max_attempts, status, expires_at, completed_at
                  from portal.access_challenge, generate_series(1, ?)
                 where id = ?
                """, copies, challenge));
    }

    @Test
    void accessCodeOnlyWithAuthenticationTemplateAndThatTemplateIsReservedForIt() {
        // o template de autenticação não sai pelo envio comum (operador, campanha, assistente)
        assertThatThrownBy(() -> asManager(() -> messages.send(new MessageUseCases.SendMessageCommand(null, maria, creditorA,
                null, "WHATSAPP", null, "PORTAL_CODIGO_WHATSAPP", Map.of("codigo", "123456"), null, null, null))))
                .hasFieldOrPropertyWithValue("code", "message.authentication_template_reserved");
        // versão nova do template do código com outra finalidade: o código não é enviado (a resposta é a mesma)
        publishWhatsAppTemplate("PORTAL_CODIGO_WHATSAPP", "Seu código é {{codigo}}.",
                List.of(new VariableInput("codigo", "CODE", true, 15)), null);
        UUID challenge = requestCode(CPF_MARIA);
        drainJobs("communication.");
        assertThat(count("select count(*) from portal.access_challenge where id = ? and message_id is null", challenge))
                .isEqualTo(1);
        assertThat(count("select count(*) from communication.message where conversation_id in "
                + "(select id from communication.conversation where debtor_id = ?)", maria)).isZero();
    }

    @Test
    void unknownDocumentGetsTheSameAnswerAndIsNeverAccepted() {
        Response known = post("/api/portal/v1/sites/" + slug + "/access/code",
                Map.of("document", CPF_MARIA, "channel", "WHATSAPP"), null);
        Response unknown = post("/api/portal/v1/sites/" + slug + "/access/code",
                Map.of("document", CPF_UNKNOWN, "channel", "WHATSAPP"), null);
        assertThat(unknown.status()).isEqualTo(known.status()).isEqualTo(202);
        assertThat(fieldNames(unknown)).isEqualTo(fieldNames(known));
        assertThat(unknown.json().get("knowledgeChallenge").asString()).isEqualTo(known.json().get("knowledgeChallenge").asString());
        UUID decoy = UUID.fromString(unknown.json().get("challengeId").asString());
        // desafio fictício: nenhuma mensagem enviada, e nenhum código o aceita
        assertThat(count("select count(*) from portal.access_challenge where id = ? and debtor_id is null and message_id is null",
                decoy)).isEqualTo(1);
        Response attempt = verify(decoy, "123456", null);
        assertThat(attempt.status()).isEqualTo(401);
        assertThat(attempt.json().get("code").asString()).isEqualTo("portal.access_invalid");
        // devedor sem dívida nas carteiras do portal também recebe resposta idêntica (desafio fictício)
        assertThat(post("/api/portal/v1/sites/" + slug + "/access/code",
                Map.of("document", "45.997.418/0001-53", "channel", "WHATSAPP"), null).status()).isEqualTo(202);
        // portal inexistente: 404 sem detalhes
        assertThat(get("/api/portal/v1/sites/nao-existe", null).status()).isEqualTo(404);
    }

    @Test
    void progressiveLockByDocumentAppliesEvenToUnknownDocuments() {
        for (String cpf : List.of(CPF_MARIA, CPF_UNKNOWN)) {
            UUID challenge = requestCode(cpf);
            for (int i = 0; i < 4; i++) {
                assertThat(verify(challenge, "999999", null).status()).isEqualTo(401);
            }
            // a 5ª falha bloqueia o documento (mesmo comportamento para documento existente e inexistente)
            assertThat(verify(challenge, "999999", null).status()).isEqualTo(401);
            Response locked = post("/api/portal/v1/sites/" + slug + "/access/code",
                    Map.of("document", cpf, "channel", "WHATSAPP"), null);
            assertThat(locked.status()).isEqualTo(429);
            assertThat(locked.json().get("code").asString()).isEqualTo("portal.access_locked");
            assertThat(locked.json().get("retryAfterSeconds").asLong()).isGreaterThan(60);
        }
        assertThat(count("select count(*) from shared.access_log where event_type = 'PORTAL_LOCKED' and tenant_id = ?",
                tenant)).isGreaterThanOrEqualTo(2);
        // o pedido de código também é limitado por documento (mais de 3 na janela bloqueia)
        for (int i = 0; i < 3; i++) {
            requestCode(CPF_JOAO);
        }
        assertThat(post("/api/portal/v1/sites/" + slug + "/access/code", Map.of("document", CPF_JOAO, "channel", "WHATSAPP"),
                null).status()).isEqualTo(429);
    }

    @Test
    void singleUseLinkAndKnowledgeChallenge() {
        AccessLinkView link = sendLink(maria);
        assertThat(link.messageStatus()).isEqualTo("QUEUED");
        String token = linkToken(link);
        Response redeemed = post("/api/portal/v1/sites/" + slug + "/access/link", Map.of("token", token), null);
        assertThat(redeemed.status()).isEqualTo(200);
        assertThat(redeemed.json().get("session").get("accessToken").asString()).isNotBlank();
        // uso único
        Response again = post("/api/portal/v1/sites/" + slug + "/access/link", Map.of("token", token), null);
        assertThat(again.status()).isEqualTo(401);
        // novo link revoga o anterior ainda não usado
        AccessLinkView first = sendLink(joao);
        AccessLinkView second = sendLink(joao);
        assertThat(post("/api/portal/v1/sites/" + slug + "/access/link", Map.of("token", linkToken(first)), null).status())
                .isEqualTo(401);
        assertThat(count("select count(*) from shared.access_log where event_type = 'PORTAL_LINK_ISSUED' and tenant_id = ?",
                tenant)).isEqualTo(3);

        // credor passa a exigir a data de nascimento: link → etapa de conhecimento
        asAdmin(() -> portalConfigurations.update(portal.id(), configuration(slug, "BIRTH_DATE")));
        Response step = post("/api/portal/v1/sites/" + slug + "/access/link", Map.of("token", linkToken(second)), null);
        assertThat(step.status()).isEqualTo(200);
        assertThat(step.json().get("session").isNull()).isTrue();
        UUID knowledge = UUID.fromString(step.json().get("knowledgeStep").get("challengeId").asString());
        assertThat(step.json().get("knowledgeStep").get("codeRequired").asBoolean()).isFalse();
        assertThat(verify(knowledge, null, "01/01/1990").status()).isEqualTo(401);
        Response ok = verify(knowledge, null, "15/03/1985");
        assertThat(ok.status()).isEqualTo(200);

        // código + conhecimento: código certo com data errada não entra
        UUID challenge = requestCode(CPF_MARIA);
        String code = sentCode(challenge);
        assertThat(verify(challenge, code, null).status()).isEqualTo(401);
        assertThat(verify(challenge, code, "1985-03-15").status()).isEqualTo(200);
    }

    @Test
    void portalAndInternalSessionsNeverMix() {
        String token = login(CPF_MARIA);
        // token do devedor não acessa a API interna
        assertThat(get("/api/v1/creditors", token).status()).isEqualTo(403);
        assertThat(get("/api/v1/negotiation/agreements", token).status()).isEqualTo(403);
        assertThat(count("select count(*) from shared.access_log where event_type = 'ACCESS_DENIED' and actor_id = ?", maria))
                .isGreaterThanOrEqualTo(1);
        // sem token: 401
        assertThat(get("/api/portal/v1/me/session", null).status()).isEqualTo(401);
        // token interno forjado com a audiência do portal e vice-versa: recusados pelo decodificador
        assertThat(get("/api/portal/v1/me/session", forged(TokenClaims.TYPE_PORTAL, TokenClaims.AUDIENCE)).status())
                .isEqualTo(401);
        assertThat(get("/api/v1/creditors", forged(TokenClaims.TYPE_ACCESS, TokenClaims.PORTAL_AUDIENCE)).status())
                .isEqualTo(401);

        // revogação pela gestão encerra a sessão (o resolvedor revalida no servidor)
        UUID sessionId = UUID.fromString(get("/api/portal/v1/me/session", token).json().get("sessionId").asString());
        asAdmin(() -> portalConfigurations.revokeSession(sessionId, "teste"));
        awaitTrue("sessão revogada", () -> get("/api/portal/v1/me/session", token).status() == 401);
        assertThat(count("select count(*) from shared.access_log where event_type = 'PORTAL_SESSION_REVOKED' and tenant_id = ?",
                tenant)).isEqualTo(1);

        // saída do devedor
        String other = login(CPF_JOAO);
        assertThat(send(HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port
                + "/api/portal/v1/me/session/logout")).POST(HttpRequest.BodyPublishers.noBody()), other).status()).isEqualTo(204);
        awaitTrue("sessão encerrada", () -> get("/api/portal/v1/me/session", other).status() == 401);
    }

    @Test
    void disabledPortalIsNotFoundAndLinkRequiresDebtInPortal() {
        // devedor só com dívida em carteira não habilitada não recebe link e não entra pelo documento
        UUID carlos = system(() -> {
            UUID id = ingestion.upsertDebtor(new br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort
                    .DebtorRecord("870.424.856-29", "Carlos Fora", null, List.of())).id();
            ingestion.upsertTitle(new br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.TitleRecord(
                    creditorA, portfolioA2, id, "CT-9", null, 1, today.minusDays(10), new java.math.BigDecimal("100.00"),
                    null, null), br.com.gestorcobranca.shared.domain.Ids.newId());
            return id;
        });
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> sendLink(carlos))
                .hasMessageContaining("carteiras habilitadas");
        UUID decoy = requestCode("870.424.856-29");
        assertThat(count("select count(*) from portal.access_challenge where id = ? and debtor_id is null", decoy))
                .isEqualTo(1);
        var disabled = configuration(slug, "NONE");
        asAdmin(() -> portalConfigurations.update(portal.id(), new br.com.gestorcobranca.portal.application.port.in
                .PortalConfigurationUseCases.ConfigurationCommand(disabled.creditorId(), disabled.slug(), false,
                disabled.displayName(), disabled.primaryColor(), disabled.accentColor(), disabled.portfolioIds(),
                disabled.otpChannels(), disabled.knowledgeChallenge(), disabled.linkValidityHours(),
                disabled.privacyNoticeText(), disabled.supportText())));
        assertThat(get("/api/portal/v1/sites/" + slug, null).status()).isEqualTo(404);
        assertThat(post("/api/portal/v1/sites/" + slug + "/access/code", Map.of("document", CPF_MARIA, "channel", "WHATSAPP"),
                null).status()).isEqualTo(404);
    }

    @Test
    void debtorAnonymizationRevokesSessionsAndErasesPortalPersonalData() {
        String token = login(CPF_MARIA);
        AccessLinkView link = sendLink(maria);
        br.com.gestorcobranca.shared.events.debt.DebtorAnonymized event = new br.com.gestorcobranca.shared.events.debt
                .DebtorAnonymized(br.com.gestorcobranca.shared.domain.Ids.newId(), tenant, Instant.now(), maria);
        tenantExecution.inTenant(tenant, "test.publish", () -> publisher.publishEvent(event));
        awaitTrue("anonimização consumida", () -> count("select count(*) from shared.consumed_event "
                + "where consumer = 'portal.debtor-anonymized' and event_id = ?", event.eventId()) == 1);
        assertThat(count("select count(*) from portal.portal_session where debtor_id = ? and status = 'ACTIVE'", maria)).isZero();
        assertThat(count("select count(*) from portal.portal_session where debtor_id = ? and client_ip is not null", maria))
                .isZero();
        assertThat(count("select count(*) from portal.access_challenge where debtor_id = ? and document_hash is not null",
                maria)).isZero();
        assertThat(post("/api/portal/v1/sites/" + slug + "/access/link", Map.of("token", linkToken(link)), null).status())
                .isEqualTo(401);
        awaitTrue("sessão revogada", () -> get("/api/portal/v1/me/session", token).status() == 401);
    }

    private String forged(String type, String audience) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer(jwtProperties.issuer()).audience(List.of(audience))
                .subject(maria.toString()).issuedAt(now).expiresAt(now.plusSeconds(300))
                .claim(TokenClaims.TYPE, type).claim(TokenClaims.TENANT, tenant.toString())
                .claim(TokenClaims.SESSION, UUID.randomUUID().toString()).claim(TokenClaims.CREDITOR, creditorA.toString())
                .build();
        return jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims))
                .getTokenValue();
    }

    private static List<String> fieldNames(Response r) {
        List<String> names = new java.util.ArrayList<>();
        r.json().propertyNames().forEach(names::add);
        return names;
    }
}
