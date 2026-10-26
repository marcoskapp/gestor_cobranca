package br.com.gestorcobranca.portal;

import br.com.gestorcobranca.campaign.application.port.in.CampaignExecutionUseCases;
import br.com.gestorcobranca.campaign.application.port.in.CampaignUseCases;
import br.com.gestorcobranca.campaign.application.port.in.CampaignUseCases.CampaignSettings;
import br.com.gestorcobranca.campaign.application.port.in.CampaignUseCases.CampaignView;
import br.com.gestorcobranca.campaign.application.port.in.CampaignUseCases.CreateCampaignCommand;
import br.com.gestorcobranca.campaign.application.port.in.SegmentUseCases;
import br.com.gestorcobranca.campaign.application.port.in.SegmentUseCases.CreateSegmentCommand;
import br.com.gestorcobranca.campaign.domain.model.ActionType;
import br.com.gestorcobranca.campaign.domain.model.CadenceDefinition;
import br.com.gestorcobranca.campaign.domain.model.CadenceStep;
import br.com.gestorcobranca.campaign.domain.model.ReplyTransition;
import br.com.gestorcobranca.campaign.domain.model.SegmentCriteria;
import br.com.gestorcobranca.campaign.domain.model.StepAction;
import br.com.gestorcobranca.communication.application.port.in.TemplateUseCases.VariableInput;
import br.com.gestorcobranca.negotiation.application.port.in.AgreementUseCases;
import br.com.gestorcobranca.negotiation.application.port.in.AgreementUseCases.AgreementView;
import br.com.gestorcobranca.negotiation.application.port.in.AgreementUseCases.InstallmentView;
import br.com.gestorcobranca.portal.application.port.in.PortalConfigurationUseCases.ConfigurationCommand;
import br.com.gestorcobranca.portal.application.port.in.PortalLinkUseCases;
import br.com.gestorcobranca.portal.application.port.in.PortalLinkUseCases.DispatchCommand;
import br.com.gestorcobranca.portal.application.port.in.PortalLinkUseCases.IssueLinkCommand;
import br.com.gestorcobranca.portal.application.port.in.PortalLinkUseCases.LinkIssue;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.jobs.JobDrain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pendências P1–P3 dos modelos de mensagem, ponta a ponta no cenário do portal: link de uso único pedido
 * por outro módulo (mesmas regras do link do operador), lembrete de parcela de acordo pela régua com o
 * link do portal gerado no envio, e código de acesso por template de autenticação da Meta (só o código).
 */
class PortalCampaignIT extends PortalTestSupport {

    @Autowired
    PortalLinkUseCases portalLinks;
    @Autowired
    AgreementUseCases agreements;
    @Autowired
    SegmentUseCases segments;
    @Autowired
    CampaignUseCases campaigns;
    @Autowired
    CampaignExecutionUseCases execution;

    @BeforeEach
    void setUp() {
        provision("pcmp");
    }

    private LinkIssue issue(UUID debtorId, UUID portfolioId) {
        return system(() -> portalLinks.issue(new IssueLinkCommand(debtorId, creditorA, portfolioId, "WHATSAPP", "CAMPAIGN",
                "campaign-enrollment:teste")));
    }

    private int redeem(String token) {
        return post("/api/portal/v1/sites/" + slug + "/access/link", Map.of("token", token), null).status();
    }

    private static String token(LinkIssue link) {
        return link.url().substring(link.url().indexOf('#') + 1);
    }

    @Test
    void linkForAnotherModuleFollowsTheOperatorLinkRules() {
        // carteira fora do portal, portal desabilitado: indisponível com motivo (nada é gerado)
        assertThat(issue(maria, portfolioA2).unavailableReason()).isEqualTo(LinkIssue.PORTFOLIO_NOT_IN_PORTAL);

        // o link do operador ainda aberto é revogado pelo novo
        var operatorLink = sendLink(joao);
        LinkIssue link = issue(joao, portfolioA);
        assertThat(link.available()).isTrue();
        assertThat(link.validityHours()).isEqualTo(48);
        assertThat(link.url()).startsWith("https://").contains("/portal/" + slug + "/entrar#");
        assertThat(token(link)).matches("[A-Za-z]{45}");
        assertThat(count("select count(*) from portal.access_link where id = ? and token_hash <> ? and issued_by_type = 'SYSTEM'",
                link.linkId(), token(link))).isEqualTo(1);
        assertThat(redeem(linkToken(operatorLink))).isEqualTo(401);

        // mensagem negada: o link é revogado na hora
        system(() -> {
            portalLinks.recordDispatch(new DispatchCommand(link.linkId(), Ids.newId(), "FAILED", "CAMPAIGN",
                    "campaign-enrollment:teste"));
            return null;
        });
        assertThat(redeem(token(link))).isEqualTo(401);

        // mensagem enfileirada: o link vale uma vez
        LinkIssue second = issue(joao, portfolioA);
        system(() -> {
            portalLinks.recordDispatch(new DispatchCommand(second.linkId(), Ids.newId(), "QUEUED", "CAMPAIGN",
                    "campaign-enrollment:teste"));
            return null;
        });
        assertThat(redeem(token(second))).isEqualTo(200);
        assertThat(redeem(token(second))).isEqualTo(401);
        assertThat(count("select count(*) from shared.access_log where event_type = 'PORTAL_LINK_ISSUED' and tenant_id = ? "
                + "and details ->> 'origin' = 'CAMPAIGN' and details ->> 'reference' = 'campaign-enrollment:teste'", tenant))
                .isEqualTo(2);
        // a trilha nunca leva o token
        assertThat(count("select count(*) from shared.access_log where tenant_id = ? and details::text like ?", tenant,
                "%" + token(second) + "%")).isZero();

        ConfigurationCommand c = configuration(slug, "NONE");
        asAdmin(() -> portalConfigurations.update(portal.id(), new ConfigurationCommand(c.creditorId(), c.slug(), false,
                c.displayName(), c.primaryColor(), c.accentColor(), c.portfolioIds(), c.otpChannels(),
                c.knowledgeChallenge(), c.linkValidityHours(), c.privacyNoticeText(), c.supportText())));
        assertThat(issue(joao, portfolioA).unavailableReason()).isEqualTo(LinkIssue.PORTAL_NOT_ENABLED);
    }

    @Test
    void installmentReminderByCadenceCarriesTheNextInstallmentAndASingleUsePortalLink() {
        // acordo à vista formalizado pela própria devedora no portal
        String token = loginReady(CPF_MARIA);
        JsonNode offers = get("/api/portal/v1/me/portfolios/" + portfolioA + "/offers", token).json();
        JsonNode cash = offers.get("offers").get(0);
        Map<String, Object> accept = new HashMap<>();
        accept.put("simulationId", offers.get("simulationId").asString());
        accept.put("level", cash.get("level").asInt());
        accept.put("installments", 1);
        accept.put("termsAccepted", true);
        assertThat(post("/api/portal/v1/me/agreements", accept, token).status()).isEqualTo(201);
        AgreementView agreement = asAdmin(() -> agreements.search(new AgreementUseCases.AgreementFilter(creditorA, maria,
                null, null), br.com.gestorcobranca.shared.paging.PageQuery.of(0, 10))).content().getFirst();
        InstallmentView parcel = agreement.installments().getFirst();
        UUID caseId = system(() -> jdbcTemplate.queryForObject(
                "select id from collection.collection_case where debtor_id = ? and portfolio_id = ? and status <> 'CLOSED'",
                UUID.class, maria, portfolioA));

        // a projeção de audiência do campaign recebe o vencimento da próxima parcela em aberto
        awaitTrue("parcela do acordo na audiência", () -> count("select count(*) from campaign.audience_case "
                + "where case_id = ? and next_installment_due_date = ?", caseId, parcel.dueDate()) == 1);

        publishWhatsAppTemplate("ACORDO_LEMBRETE_PARCELA", "Olá, {{nome}}. Lembrete do seu acordo com {{credor}}: a "
                        + "parcela de {{valor}} vence em {{vencimento}}. Para ver o acordo e pagar, acesse {{link}} (vale por "
                        + "{{horas}} horas). Para não receber mais mensagens, responda SAIR.",
                List.of(new VariableInput("nome", "FIRST_NAME", true, null), new VariableInput("credor", "TEXT", true, null),
                        new VariableInput("valor", "MONEY", true, null), new VariableInput("vencimento", "DATE", true, null),
                        new VariableInput("link", "URL", true, null), new VariableInput("horas", "INTEGER", true, null)),
                "UTILITY");
        SegmentCriteria dueSoon = new SegmentCriteria(Set.of(portfolioA), null, null, null, null, null, null, null, null,
                null, null, null, null, null, true, null, null, null, null, null, 60);
        UUID segmentId = asManager(() -> segments.create(new CreateSegmentCommand(creditorA, "PARCELA_60",
                "Parcela vencendo", null, dueSoon))).id();
        assertThat(asManager(() -> segments.count(segmentId, creditorA, null)).cases()).isEqualTo(1);
        CampaignView campaign = asManager(() -> campaigns.create(new CreateCampaignCommand("LEMBRETE_PARCELA", creditorA,
                null, new CampaignSettings("Lembrete de parcela", null, segmentId, "AGREEMENT_FOLLOW_UP", 100, null, null,
                7, null, 24, true))));
        asManager(() -> campaigns.saveDraft(campaign.id(), new CadenceDefinition(ReplyTransition.STOP, null, null,
                List.of(new CadenceStep("LEMBRETE", "Lembrete", 0, null, null, new StepAction(ActionType.SEND_MESSAGE,
                        "WHATSAPP", "ACORDO_LEMBRETE_PARCELA", Map.of("nome", "DEBTOR_FIRST_NAME", "credor",
                        "CREDITOR_NAME", "valor", "NEXT_INSTALLMENT_AMOUNT", "vencimento", "NEXT_INSTALLMENT_DUE_DATE",
                        "link", "PORTAL_LINK", "horas", "PORTAL_LINK_HOURS"), null, null, null, null, null),
                        null, null)))));
        asManager(() -> campaigns.publishDraft(campaign.id()));
        asManager(() -> execution.activate(campaign.id()));

        awaitTrue("etapa de lembrete executada", () -> {
            JobDrain.drain(applicationContext, jdbcTemplate, "campaign.");
            JobDrain.drain(applicationContext, jdbcTemplate, "communication.");
            return count("select count(*) from campaign.step_execution s join campaign.enrollment e on e.id = s.enrollment_id "
                    + "where e.campaign_id = ? and s.outcome = 'EXECUTED' and s.message_id is not null", campaign.id()) == 1;
        });
        UUID messageId = system(() -> jdbcTemplate.queryForObject("select s.message_id from campaign.step_execution s "
                + "join campaign.enrollment e on e.id = s.enrollment_id where e.campaign_id = ?", UUID.class, campaign.id()));
        String content = asAdmin(() -> messages.get(messageId)).content();
        BigDecimal due = parcel.amount().subtract(parcel.paidAmount());
        String amount = "R$ " + new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.of("pt", "BR")))
                .format(due);
        assertThat(content).startsWith("Olá, Maria.").contains("Banco A").contains(amount)
                .contains(parcel.dueDate().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))).contains("vale por 48 horas");
        assertThat(count("select count(*) from shared.access_log where event_type = 'PORTAL_LINK_ISSUED' and tenant_id = ? "
                + "and details ->> 'origin' = 'CAMPAIGN' and details ->> 'delivered' = 'true'", tenant)).isEqualTo(1);
        assertThat(count("select count(*) from portal.access_link where debtor_id = ? and message_id = ?", maria, messageId))
                .isEqualTo(1);
        String linkToken = linkToken(messageId);
        assertThat(redeem(linkToken)).isEqualTo(200);
        assertThat(redeem(linkToken)).isEqualTo(401);
    }

    @Test
    void accessCodeByWhatsAppAuthenticationTemplateCarriesOnlyTheCode() {
        // nova versão do template do código no formato de autenticação da Meta: só {{codigo}}
        publishWhatsAppTemplate("PORTAL_CODIGO_WHATSAPP",
                "{{codigo}} é o seu código de verificação. Para sua segurança, não o compartilhe.",
                List.of(new VariableInput("codigo", "CODE", true, 15)), "AUTHENTICATION");
        UUID challenge = requestCode(CPF_JOAO);
        String code = sentCode(challenge);
        assertThat(code).matches("\\d{6}");
        UUID messageId = system(() -> jdbcTemplate.queryForObject(
                "select message_id from portal.access_challenge where id = ?", UUID.class, challenge));
        assertThat(asAdmin(() -> messages.get(messageId)).status()).isNotEqualTo("FAILED");
        assertThat(verify(challenge, code, null).status()).isEqualTo(200);
    }
}
