package br.com.gestorcobranca.portal;

import br.com.gestorcobranca.collection.application.port.in.CaseUseCases;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.OpenDisputeCommand;
import br.com.gestorcobranca.compliance.application.port.in.VulnerabilityUseCases;
import br.com.gestorcobranca.compliance.application.port.in.VulnerabilityUseCases.RegisterMarkerCommand;
import br.com.gestorcobranca.debt.application.port.in.DebtTitleUseCases;
import br.com.gestorcobranca.debt.application.port.in.DebtTitleUseCases.TitleView;
import br.com.gestorcobranca.debt.application.port.in.ReversalHoldUseCases;
import br.com.gestorcobranca.debt.application.port.in.reversalhold.ReversalHoldPlacement;
import br.com.gestorcobranca.negotiation.application.port.in.NegotiationSimulationUseCases;
import br.com.gestorcobranca.negotiation.application.port.in.NegotiationSimulationUseCases.PlanInput;
import br.com.gestorcobranca.negotiation.application.port.in.NegotiationSimulationUseCases.SimulateCommand;
import br.com.gestorcobranca.negotiation.application.port.in.ProposalUseCases;
import br.com.gestorcobranca.negotiation.application.port.in.ProposalUseCases.CreateProposalCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O que o portal mostra quando a dívida não pode ser negociada: títulos suspensos ficam fora da lista, das somas e
 * da cotação (nunca "emprestando" títulos de carteira fora do portal); devedor só com suspensos não entra (resposta
 * idêntica à de documento sem cadastro); dívida retida no jurídico mostra o aviso próprio só quando a retenção é o
 * único motivo — com marcador de vulnerabilidade, o aviso continua genérico.
 */
class PortalDebtAvailabilityIT extends PortalTestSupport {

    @Autowired
    DebtTitleUseCases titles;
    @Autowired
    CaseUseCases cases;
    @Autowired
    DisputeUseCases disputes;
    @Autowired
    VulnerabilityUseCases vulnerability;
    @Autowired
    NegotiationSimulationUseCases simulations;
    @Autowired
    ProposalUseCases proposals;
    @Autowired
    ReversalHoldPlacement placement;
    @Autowired
    ReversalHoldUseCases reversalHolds;
    @Autowired
    org.springframework.transaction.support.TransactionTemplate transactions;

    @BeforeEach
    void setUp() {
        provision("pdisp");
    }

    @Test
    void suspendedTitlesAreNotShownSummedOrQuoted() {
        suspend("CT-0002-5432");
        String token = loginReady(CPF_MARIA);

        JsonNode debt = onlyPortfolio(token);
        assertThat(debt.get("titles").size()).isEqualTo(1);
        assertThat(debt.get("titles").get(0).get("status").asString()).isEqualTo("OPEN");
        assertThat(new BigDecimal(debt.get("openBalance").asString())).isEqualByComparingTo("1000.00");
        assertThat(debt.get("negotiable").asBoolean()).isTrue();
    }

    @Test
    void openSessionNeverQuotesTitlesOutsideThePortalWhenThePortalPortfolioBecomesAllSuspended() {
        String token = loginReady(CPF_MARIA);
        suspend("CT-0001-9876");
        suspend("CT-0002-5432");
        // a A-02 (fora do portal) continua com título em aberto: não pode ser cotada nem aparecer
        Response debts = get("/api/portal/v1/me/debts", token);
        assertThat(debts.status()).isEqualTo(200);
        assertThat(debts.json().get("portfolios").size()).isZero();
        assertThat(get("/api/portal/v1/me/portfolios/" + portfolioA + "/offers", token).status()).isEqualTo(404);
        assertThat(count("select count(*) from negotiation.simulation where debtor_id = ?", maria)).isZero();
    }

    @Test
    void debtorWithOnlySuspendedTitlesInThePortalDoesNotEnterAndGetsTheSameAnswerAsAnUnknownDocument() {
        suspend("CT-0001-9876");
        suspend("CT-0002-5432");
        // a carteira A-02 (fora do portal) continua com título em aberto: não pode aparecer nem destravar a entrada
        Response known = post("/api/portal/v1/sites/" + slug + "/access/code",
                Map.of("document", CPF_MARIA, "channel", "WHATSAPP"), null);
        Response unknown = post("/api/portal/v1/sites/" + slug + "/access/code",
                Map.of("document", CPF_UNKNOWN, "channel", "WHATSAPP"), null);
        assertThat(known.status()).isEqualTo(unknown.status()).isEqualTo(202);
        UUID challenge = UUID.fromString(known.json().get("challengeId").asString());
        assertThat(count("select count(*) from portal.access_challenge where id = ? and debtor_id is null "
                + "and message_id is null", challenge)).isEqualTo(1);
    }

    @Test
    void debtorWithAnActiveAgreementStillEntersEvenWithEveryTitleSuspended() {
        UUID sim = asOperator(() -> simulations.simulate(new SimulateCommand(creditorA, maria,
                openTitles(maria, portfolioA), null, null, null, List.of(new PlanInput(1, null, null)), "PHONE")).id());
        UUID proposal = asOperator(() -> proposals.create(new CreateProposalCommand(sim, 0, null,
                new PlanInput(1, null, null), null, null, false, null)).id());
        asOperator(() -> proposals.accept(proposal, new ProposalUseCases.AcceptCommand("PHONE", "call-1", null, null)));
        UUID agreement = asOperator(() -> proposals.formalize(proposal)).id();
        suspend("CT-0001-9876");
        suspend("CT-0002-5432");

        String token = loginReady(CPF_MARIA);
        assertThat(get("/api/portal/v1/me/debts", token).json().get("portfolios").size()).isZero();
        JsonNode agreements = get("/api/portal/v1/me/agreements", token).json();
        assertThat(agreements.size()).isEqualTo(1);
        assertThat(agreements.get(0).get("id").asString()).isEqualTo(agreement.toString());
    }

    @Test
    void legalHoldShowsItsOwnNoticeOnlyWhenItIsTheOnlyReason() {
        String token = loginReady(CPF_MARIA);
        UUID caseId = asManager(() -> cases.findActive(maria, portfolioA)).orElseThrow().id();
        asManager(() -> cases.sendToLegal(caseId, "Atuação jurídica autorizada"));

        JsonNode debt = onlyPortfolio(token);
        assertThat(debt.get("negotiable").asBoolean()).isFalse();
        assertThat(debt.get("unavailableReason").asString()).isEqualTo("LEGAL_HOLD");
        assertThat(debt.get("updatedAmount").isNull()).isTrue();
        debt.get("titles").forEach(t -> assertThat(t.get("inOffer").asBoolean()).isFalse());
        // pelo endereço direto das condições: recusa sem condição nenhuma
        Response offers = get("/api/portal/v1/me/portfolios/" + portfolioA + "/offers", token);
        assertThat(offers.status()).isBetween(400, 499);

        // outro motivo sensível junto (marcador de vulnerabilidade): o aviso volta ao genérico
        asManager(() -> vulnerability.register(new RegisterMarkerCommand(maria, "SUPER_INDEBTED", "LEGAL_OBLIGATION",
                "DEBTOR_DECLARATION", null)));
        assertThat(onlyPortfolio(token).get("unavailableReason").asString()).isEqualTo("NOT_ELIGIBLE");
    }

    /**
     * SEG-20261126-PA05 (decisão 1682): contestação só de uma parcela — ela continua em aberto na lista, mas fica fora
     * do valor atualizado e das condições; a tela sabe quais (inOffer) e quantas (titlesOutside), nunca o motivo.
     */
    @Test
    void titleLeftOutOfTheQuoteIsFlaggedWithoutTheReason() {
        TitleView disputed = system(() -> titles.listByDebtor(maria)).stream()
                .filter(t -> "CT-0002-5432".equals(t.externalId())).findFirst().orElseThrow();
        String token = loginReady(CPF_MARIA);
        BigDecimal before = new BigDecimal(onlyPortfolio(token).get("updatedAmount").asString());
        UUID caseId = asManager(() -> cases.findActive(maria, portfolioA)).orElseThrow().id();
        asManager(() -> disputes.open(caseId, new OpenDisputeCommand(Set.of(disputed.id()), "ALREADY_PAID", "DEBTOR",
                "Contestação de teste", true, null)));
        drainJobs("collection.");

        Response debts = get("/api/portal/v1/me/debts", token);
        assertThat(debts.status()).isEqualTo(200);
        assertThat(debts.body()).doesNotContain("dispute").doesNotContain("ALREADY_PAID");
        JsonNode debt = onlyPortfolio(token);
        assertThat(debt.get("negotiable").asBoolean()).isTrue();
        // o cartão passa a valer só para a parcela que entra
        assertThat(new BigDecimal(debt.get("updatedAmount").asString())).isLessThan(before);
        assertThat(debt.get("titles").size()).isEqualTo(2);
        for (JsonNode t : debt.get("titles")) {
            boolean isDisputed = disputed.id().toString().equals(t.get("titleId").asString());
            assertThat(t.get("inOffer").asBoolean()).isEqualTo(!isDisputed);
            assertThat(t.has("reasons")).isFalse();
        }
        Response offers = get("/api/portal/v1/me/portfolios/" + portfolioA + "/offers", token);
        assertThat(offers.status()).isEqualTo(200);
        assertThat(offers.json().get("titlesOutside").asInt()).isEqualTo(1);
        assertThat(offers.body()).doesNotContain("dispute").doesNotContain("ALREADY_PAID");
    }

    @Test
    void everyTitleIsInTheOfferWhenNothingIsLeftOut() {
        String token = loginReady(CPF_MARIA);
        JsonNode debt = onlyPortfolio(token);
        for (JsonNode t : debt.get("titles")) {
            assertThat(t.get("inOffer").asBoolean()).isTrue();
        }
        assertThat(get("/api/portal/v1/me/portfolios/" + portfolioA + "/offers", token).json()
                .get("titlesOutside").asInt()).isZero();
    }

    /** H07 parte 3, decisão do responsável 4 (RS-112): título com estorno em conferência não aparece nem soma. */
    @Test
    void titleWithReversalInReviewIsNotShownNorSummed() {
        TitleView held = system(() -> titles.listByDebtor(maria)).stream()
                .filter(t -> "CT-0002-5432".equals(t.externalId())).findFirst().orElseThrow();
        system(() -> transactions.execute(tx -> placement.place(new ReversalHoldPlacement.PlaceCommand(
                UUID.randomUUID(), UUID.randomUUID(), creditorA, maria, null, List.of(held.id()), List.of(), List.of(),
                null, "OTHER"))));
        drainJobs("collection.");
        String token = loginReady(CPF_MARIA);

        JsonNode debt = onlyPortfolio(token);
        assertThat(debt.get("titles").size()).isEqualTo(1);
        assertThat(debt.get("titles").get(0).get("titleId").asString()).isNotEqualTo(held.id().toString());
        assertThat(new BigDecimal(debt.get("openBalance").asString())).isEqualByComparingTo("1000.00");
        // decisão 1655: só o título retido sai da negociação; o outro título continua negociável no portal
        assertThat(debt.get("negotiable").asBoolean()).isTrue();
    }

    private JsonNode onlyPortfolio(String token) {
        Response r = get("/api/portal/v1/me/debts", token);
        assertThat(r.status()).isEqualTo(200);
        JsonNode portfolios = r.json().get("portfolios");
        assertThat(portfolios.size()).isEqualTo(1);
        assertThat(portfolios.get(0).get("portfolioId").asString()).isEqualTo(portfolioA.toString());
        return portfolios.get(0);
    }

    private void suspend(String externalId) {
        TitleView title = system(() -> titles.listByDebtor(maria)).stream()
                .filter(t -> externalId.equals(t.externalId())).findFirst().orElseThrow();
        asManager(() -> titles.suspend(title.id(), "Em análise pelo credor", null));
        drainJobs("collection.");
    }
}
