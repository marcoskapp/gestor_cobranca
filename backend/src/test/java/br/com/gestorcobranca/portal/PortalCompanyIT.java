package br.com.gestorcobranca.portal;

import br.com.gestorcobranca.debt.application.port.in.CompanyRepresentativeUseCases;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.ContactRecord;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.DebtorRecord;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.ImportSummary;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.RepresentativeRecord;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.TitleRecord;
import br.com.gestorcobranca.shared.domain.Ids;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Portal para pessoa jurídica (cobrança B2B, B4 — docs/tecnico/desenho/cobranca-b2b.md §5.2 e §10): desafio de
 * conhecimento de PJ, representante que confere e que não confere — CPF ou nome — (mesma resposta), aceite com representante no termo e
 * no registro do acordo, aceite sem representante confirmado vira atendimento, e o limite de tentativas persistido entre
 * sessões. Dados fictícios.
 */
class PortalCompanyIT extends PortalTestSupport {

    static final String CNPJ = "60.012.345/0001-97";
    static final String PARTNER_CPF = "123.456.789-09";
    static final String WRONG_CPF = "987.654.321-00";
    /** primeiro e último nome do cadastro ("João Sócio Fictício"), sem acento: confere */
    static final String PARTNER_NAME = "Joao Ficticio";

    @Autowired
    CompanyRepresentativeUseCases representatives;

    UUID company;

    @BeforeEach
    void setUp() {
        provision("pco");
        company = system(() -> ingestion.upsertDebtor(new DebtorRecord(CNPJ, "Comercial Fictícia de Pães Ltda", null,
                List.of(new ContactRecord("PHONE", "(11) 96666-1234", "MOBILE", true, "Ana Fictícia", "FINANCE")),
                "Padaria Fictícia",
                List.of(new RepresentativeRecord(PARTNER_CPF, "João Sócio Fictício", "MANAGING_PARTNER", true)))).id());
        UUID batch = Ids.newId();
        system(() -> {
            ingestion.upsertTitle(new TitleRecord(creditorA, portfolioA, company, "CT-0009-7777", null, 1,
                    today.minusDays(40), new BigDecimal("2000.00"), null, null), batch);
            ingestion.completeImport(new ImportSummary(batch, creditorA, portfolioA, 1, 0, 1));
            return null;
        });
        awaitTrue("sincronização da carteira da empresa agendada", () -> count("select count(*) from shared.processing_job "
                + "where type = 'collection.portfolio_sync' and tenant_id = ?", tenant) >= 3);
        drainJobs("collection.");
        awaitTrue("caso da empresa aberto", () -> count(
                "select count(*) from collection.collection_case where debtor_id = ?", company) == 1);
    }

    @Test
    void companyAnswersTheContractSuffixWhenTheCreditorAsksForTheBirthDate() {
        asAdmin(() -> portalConfigurations.update(portal.id(), configuration(slug, "BIRTH_DATE")));
        Response requested = post("/api/portal/v1/sites/" + slug + "/access/code", Map.of("document", CNPJ,
                "channel", "WHATSAPP"), null);
        assertThat(requested.status()).isEqualTo(202);
        assertThat(requested.json().get("knowledgeChallenge").asString()).isEqualTo("CONTRACT_SUFFIX");
        UUID challenge = UUID.fromString(requested.json().get("challengeId").asString());
        String code = sentCode(challenge);
        assertThat(verify(challenge, code, "15/03/1985").status()).isEqualTo(401);

        UUID second = requestCode(CNPJ);
        assertThat(verify(second, sentCode(second), "7777").status()).isEqualTo(200);

        // CNPJ sem cadastro recebe o mesmo desafio (decidido pelo documento digitado, não pelo cadastro)
        Response decoy = post("/api/portal/v1/sites/" + slug + "/access/code", Map.of("document", "70.098.765/0001-91",
                "channel", "WHATSAPP"), null);
        assertThat(decoy.json().get("knowledgeChallenge").asString()).isEqualTo("CONTRACT_SUFFIX");
        // pessoa física continua com a data de nascimento
        Response individual = post("/api/portal/v1/sites/" + slug + "/access/code", Map.of("document", CPF_JOAO,
                "channel", "WHATSAPP"), null);
        assertThat(individual.json().get("knowledgeChallenge").asString()).isEqualTo("BIRTH_DATE");
    }

    @Test
    void confirmedRepresentativeClosesTheAgreementAndGoesToTheTermAndTheRecord() {
        String token = loginReady(CNPJ);
        JsonNode session = get("/api/portal/v1/me/session", token).json();
        assertThat(session.get("company").asBoolean()).isTrue();
        assertThat(session.get("firstName").asString()).isEqualTo("Padaria Fictícia");
        JsonNode before = get("/api/portal/v1/me/representative", token).json();
        assertThat(before.get("required").asBoolean()).isTrue();
        assertThat(before.get("declared").asBoolean()).isFalse();
        assertThat(before.get("humanCompletion").asBoolean()).isFalse();

        Offer simulationId = simulation(token);
        Response early = post("/api/portal/v1/me/agreements", accept(simulationId), token);
        assertThat(early.status()).isEqualTo(409);
        assertThat(early.json().get("code").asString()).isEqualTo("portal.representative_required");

        Response declared = declare(token, PARTNER_CPF, PARTNER_NAME);
        assertThat(declared.status()).isEqualTo(200);
        assertThat(declared.json().get("declared").asBoolean()).isTrue();

        Map<String, Object> noChannel = accept(simulationId);
        noChannel.remove("preferredChannel");
        assertThat(post("/api/portal/v1/me/agreements", noChannel, token).json().get("code").asString())
                .isEqualTo("portal.channel_required");

        Response accepted = post("/api/portal/v1/me/agreements", accept(simulationId), token);
        assertThat(accepted.status()).isEqualTo(201);
        assertThat(accepted.json().get("serviceRequest").isNull()).isTrue();
        UUID agreementId = UUID.fromString(accepted.json().get("agreement").get("id").asString());
        UUID representativeId = system(() -> representatives.list(company)).getFirst().id();
        assertThat(count("select count(*) from portal.portal_agreement where agreement_id = ? and representative_id = ? "
                + "and representative_role = 'MANAGING_PARTNER'", agreementId, representativeId)).isEqualTo(1);
        assertThat(count("select count(*) from portal.portal_session where debtor_id = ? and representative_status = "
                + "'CONFIRMED' and representative_document_masked = '***.456.789-**'", company)).isEqualTo(1);
        // o CPF digitado nunca vai em claro ao banco do portal nem ao log de acesso
        assertThat(count("select count(*) from portal.portal_session where representative_document_masked like '%12345678909%'"))
                .isZero();
        assertThat(count("select count(*) from shared.access_log where details::text like '%12345678909%' "
                + "or details::text like '%123.456.789-09%'")).isZero();
    }

    @Test
    void cpfThatDoesNotMatchGetsTheSameAnswerAndTheAcceptanceGoesToAHuman() {
        String token = loginReady(CNPJ);
        Response wrong = declare(token, WRONG_CPF, PARTNER_NAME);
        String other = loginReady(CNPJ);
        Response right = declare(other, PARTNER_CPF, PARTNER_NAME);
        assertThat(wrong.status()).isEqualTo(right.status()).isEqualTo(200);
        assertThat(wrong.body()).isEqualTo(right.body());

        Response accepted = post("/api/portal/v1/me/agreements", accept(simulation(token)), token);
        assertThat(accepted.status()).isEqualTo(201);
        assertThat(accepted.json().get("agreement").isNull()).isTrue();
        JsonNode request = accepted.json().get("serviceRequest");
        assertThat(request.get("reason").asString()).isEqualTo("COMPANY_ACCEPTANCE");
        assertThat(count("select count(*) from portal.service_request where debtor_id = ? and reason = 'COMPANY_ACCEPTANCE'",
                company)).isEqualTo(1);
        assertThat(count("select count(*) from negotiation.agreement where debtor_id = ?", company)).isZero();
        // o devedor não escolhe esse motivo pelo pedido de atendimento
        assertThat(post("/api/portal/v1/me/service-requests", Map.of("reason", "COMPANY_ACCEPTANCE",
                "preferredChannel", "WHATSAPP"), token).json().get("code").asString())
                .isEqualTo("portal.request_reason_invalid");
    }

    @Test
    void rightCpfWithWrongNameGetsTheSameAnswerAndTheAcceptanceGoesToAHuman() {
        String token = loginReady(CNPJ);
        // mesmo primeiro nome (a resposta mostra só ele) e último nome diferente do cadastro
        Response wrongName = declare(token, PARTNER_CPF, "João Outro");
        // conta como falha no contador da empresa (o acerto seguinte zera)
        assertThat(count("select count(*) from portal.access_throttle where tenant_id = ? and key_type = 'REP_DEBTOR' "
                + "and failures = 1", tenant)).isEqualTo(1);
        String other = loginReady(CNPJ);
        Response right = declare(other, PARTNER_CPF, "João Fictício");
        assertThat(wrongName.status()).isEqualTo(right.status()).isEqualTo(200);
        assertThat(wrongName.body()).isEqualTo(right.body());

        Response accepted = post("/api/portal/v1/me/agreements", accept(simulation(token)), token);
        assertThat(accepted.status()).isEqualTo(201);
        assertThat(accepted.json().get("agreement").isNull()).isTrue();
        assertThat(accepted.json().get("serviceRequest").get("reason").asString()).isEqualTo("COMPANY_ACCEPTANCE");
        assertThat(count("select count(*) from negotiation.agreement where debtor_id = ?", company)).isZero();
        assertThat(count("select count(*) from portal.portal_session where debtor_id = ? and representative_status = "
                + "'CONFIRMED'", company)).isEqualTo(1);
    }

    @Test
    void withoutRepresentativeWithPowerTheAcceptanceIsAlwaysCompletedByAHuman() {
        UUID representativeId = system(() -> representatives.list(company)).getFirst().id();
        system(() -> representatives.end(company, representativeId, "Saiu da sociedade"));
        String token = loginReady(CNPJ);
        assertThat(get("/api/portal/v1/me/representative", token).json().get("humanCompletion").asBoolean()).isTrue();
        declare(token, PARTNER_CPF, PARTNER_NAME);
        Response accepted = post("/api/portal/v1/me/agreements", accept(simulation(token)), token);
        assertThat(accepted.json().get("serviceRequest").get("reason").asString()).isEqualTo("COMPANY_ACCEPTANCE");
    }

    @Test
    void failuresAreCountedPerCompanyAcrossRenewedSessions() {
        String first = loginReady(CNPJ);
        for (int i = 0; i < 5; i++) {
            assertThat(declare(first, WRONG_CPF, "Tentativa").status()).isEqualTo(200);
        }
        assertThat(count("select count(*) from portal.access_throttle where tenant_id = ? and key_type = 'REP_DEBTOR' "
                + "and locked_until > now()", tenant)).isEqualTo(1);
        // nova sessão não zera: o CPF certo é registrado sem conferir e o aceite vai para um atendente
        String renewed = loginReady(CNPJ);
        assertThat(declare(renewed, PARTNER_CPF, PARTNER_NAME).status()).isEqualTo(200);
        Response accepted = post("/api/portal/v1/me/agreements", accept(simulation(renewed)), renewed);
        assertThat(accepted.json().get("serviceRequest").get("reason").asString()).isEqualTo("COMPANY_ACCEPTANCE");
        assertThat(count("select count(*) from portal.portal_session where debtor_id = ? and representative_status = "
                + "'CONFIRMED'", company)).isZero();
    }

    @Test
    void individualDebtorNeverDeclaresARepresentative() {
        String token = loginReady(CPF_MARIA);
        assertThat(get("/api/portal/v1/me/representative", token).json().get("required").asBoolean()).isFalse();
        Response refused = declare(token, PARTNER_CPF, "Maria");
        assertThat(refused.status()).isEqualTo(422);
        assertThat(refused.json().get("code").asString()).isEqualTo("portal.representative_not_applicable");
        assertThat(get("/api/portal/v1/me/session", token).json().get("company").asBoolean()).isFalse();
        // CPF malformado: validação de formato, sem dizer nada do cadastro
        String companyToken = loginReady(CNPJ);
        assertThat(declare(companyToken, "123.456.789-00", "João").json().get("code").asString())
                .isEqualTo("portal.representative_cpf_invalid");
    }

    private Response declare(String token, String cpf, String name) {
        return post("/api/portal/v1/me/representative", Map.of("cpf", cpf, "name", name), token);
    }

    /** Oferta à vista da carteira (simulação registrada no negotiation). */
    private record Offer(UUID simulationId, int level) {
    }

    private Offer simulation(String token) {
        Response offers = get("/api/portal/v1/me/portfolios/" + portfolioA + "/offers", token);
        assertThat(offers.status()).isEqualTo(200);
        JsonNode cash = offers.json().get("offers").get(0);
        assertThat(cash.get("installments").asInt()).isEqualTo(1);
        return new Offer(UUID.fromString(offers.json().get("simulationId").asString()), cash.get("level").asInt());
    }

    private static Map<String, Object> accept(Offer offer) {
        Map<String, Object> m = new HashMap<>();
        m.put("simulationId", offer.simulationId().toString());
        m.put("level", offer.level());
        m.put("installments", 1);
        m.put("termsAccepted", true);
        m.put("preferredChannel", "WHATSAPP");
        return m;
    }
}
