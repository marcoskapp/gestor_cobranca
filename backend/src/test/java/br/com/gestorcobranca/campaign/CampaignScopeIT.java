package br.com.gestorcobranca.campaign;

import br.com.gestorcobranca.campaign.application.port.in.CampaignUseCases.CampaignFilter;
import br.com.gestorcobranca.campaign.application.port.in.CampaignUseCases.CampaignView;
import br.com.gestorcobranca.campaign.application.port.in.CampaignUseCases.CreateCampaignCommand;
import br.com.gestorcobranca.campaign.application.port.in.SegmentUseCases.PreviewCommand;
import br.com.gestorcobranca.campaign.application.port.in.SegmentUseCases.SegmentFilter;
import br.com.gestorcobranca.campaign.domain.model.SegmentCriteria;
import br.com.gestorcobranca.collection.application.port.in.CaseUseCases.CaseView;
import br.com.gestorcobranca.identity.domain.model.DefaultRoles;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.ActorAuthentication;
import br.com.gestorcobranca.shared.security.CreditorScope;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.shared.security.ScopeAccessDeniedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Escopo por credor, RLS entre assessorias e contrato HTTP (permissões e JSON da régua). */
class CampaignScopeIT extends CampaignTestSupport {

    @Autowired
    WebApplicationContext webApplicationContext;

    CaseView kase;
    UUID segmentA;
    UUID tenantSegment;
    CampaignView campaignA;
    CampaignView campaignB;

    @BeforeEach
    void setUp() {
        provisionCampaigns("csc");
        UUID debtor = debtor("529.982.247-25");
        kase = loadCase(creditorA, portfolioA, debtor, "400.00", LocalDate.of(2026, 4, 1));
        segmentA = segment(creditorA, "SEG_A", SegmentCriteria.any());
        UUID segmentB = segment(creditorB, "SEG_B", SegmentCriteria.any());
        tenantSegment = segment(null, "SEG_TODOS", SegmentCriteria.any());
        campaignA = activeCampaign("CAMP_A", creditorA, settings(segmentA, null, 1),
                cadence(null, whatsapp("D0", 0, null, null), whatsapp("D9", 9 * 1440, null, null)));
        campaignB = activeCampaign("CAMP_B", creditorB, settings(segmentB, null, 1),
                cadence(null, whatsapp("D0", 0, null, null)));
        runEngine();
    }

    /** Gestor com escopo apenas do credor B. */
    private <T> T asManagerOfB(Supplier<T> action) {
        return as(Ids.newId(), DefaultRoles.GESTOR, CreditorScope.of(Set.of(creditorB)), action);
    }

    @Test
    void creditorScopeHidesAndForbidsOtherCreditorsResources() {
        assertThatThrownBy(() -> asManagerOfB(() -> campaigns.get(campaignA.id())))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> asManagerOfB(() -> campaigns.create(new CreateCampaignCommand("NOVA_A", creditorA, null,
                settings(segmentA, null, 1))))).isInstanceOf(ScopeAccessDeniedException.class);
        assertThat(asManagerOfB(() -> campaigns.search(new CampaignFilter(null, null), PageQuery.of(0, 50))).content())
                .extracting(CampaignView::id).containsExactly(campaignB.id());
        assertThatThrownBy(() -> asManagerOfB(() -> segments.get(tenantSegment)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> asManagerOfB(() -> segments.get(segmentA))).isInstanceOf(ResourceNotFoundException.class);
        assertThat(asManagerOfB(() -> segments.search(new SegmentFilter(null, null), PageQuery.of(0, 50))).content())
                .allMatch(s -> creditorB.equals(s.creditorId()));
        assertThatThrownBy(() -> asManagerOfB(() -> segments.preview(new PreviewCommand(null, null,
                SegmentCriteria.any())))).isInstanceOf(ScopeAccessDeniedException.class);
        assertThatThrownBy(() -> asManagerOfB(() -> execution.pause(campaignA.id(), "x")))
                .isInstanceOf(ResourceNotFoundException.class);
        UUID enrollmentA = enrollmentOf(campaignA.id(), kase.id()).id();
        assertThatThrownBy(() -> asManagerOfB(() -> execution.enrollment(enrollmentA)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(asManagerOfB(() -> execution.byCase(kase.id()))).isEmpty();
        assertThat(count("select count(*) from shared.access_log where event_type = 'SCOPE_DENIED' "
                + "and details ->> 'resource' = 'campaign'")).isPositive();
    }

    @Test
    void rowLevelSecurityIsolatesTenants() {
        assertThat(count("select count(*) from campaign.enrollment")).isPositive();
        assertThat(count("select count(*) from campaign.audience_case")).isPositive();
        Long otherTenant = tenantExecution.callInTenant(Ids.newId(), "test.rls", () -> jdbcTemplate.queryForObject(
                "select (select count(*) from campaign.enrollment) + (select count(*) from campaign.audience_case) "
                        + "+ (select count(*) from campaign.campaign) + (select count(*) from campaign.step_execution)",
                Long.class));
        assertThat(otherTenant).isZero();
        Long noTenant = jdbcTemplate.queryForObject("select count(*) from campaign.segment", Long.class);
        assertThat(noTenant).isZero();
    }

    @Test
    void httpContractRequiresPermissionsAndRoundTripsTheCadenceJson() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
        ActorAuthentication operator = authenticationFor(DefaultRoles.OPERADOR);
        ActorAuthentication manager = authenticationFor(DefaultRoles.GESTOR);
        mvc.perform(get("/api/v1/campaign/campaigns").with(authentication(operator))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/campaign/campaigns").with(authentication(manager)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(put("/api/v1/campaign/campaigns/" + campaignA.id() + "/cadences/draft").with(authentication(manager))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"defaultOnReply":"MOVE_TO_QUEUE","steps":[
                                  {"code":"D0","name":"Manhã","waitMinutes":0,
                                   "window":{"start":"09:00:00","end":"12:00:00","days":["MONDAY","FRIDAY"]},
                                   "condition":{"requireNoReply":true},
                                   "action":{"type":"SEND_MESSAGE","channel":"WHATSAPP","templateCode":"COBRANCA",
                                             "variables":{"nome":"DEBTOR_FIRST_NAME","valor":"OPEN_BALANCE"}},
                                   "onReply":"GOTO_STEP","onReplyStepCode":"LIGAR"},
                                  {"code":"LIGAR","waitMinutes":60,
                                   "action":{"type":"CREATE_TASK","taskType":"CALLBACK","taskDueInMinutes":30}}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.versionNumber").value(2))
                .andExpect(jsonPath("$.definition.steps[0].window.start").value("09:00:00"))
                .andExpect(jsonPath("$.definition.steps[0].window.days.length()").value(2))
                .andExpect(jsonPath("$.definition.steps[1].action.taskType").value("CALLBACK"));
        mvc.perform(put("/api/v1/campaign/campaigns/" + campaignA.id() + "/cadences/draft").with(authentication(manager))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"steps\":[]}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("cadence.steps_invalid"));
        mvc.perform(get("/api/v1/campaign/campaigns/" + campaignA.id() + "/dashboard").with(authentication(manager)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enrollments").value(1));
    }

    private ActorAuthentication authenticationFor(String role) {
        Set<Permission> permissions = DefaultRoles.forTenant().stream().filter(d -> d.code().equals(role)).findFirst()
                .orElseThrow().permissions();
        return ActorAuthentication.of(new Actor(Actor.ActorType.USER, Ids.newId(), tenant, Ids.newId(), role, permissions,
                CreditorScope.ALL));
    }
}
