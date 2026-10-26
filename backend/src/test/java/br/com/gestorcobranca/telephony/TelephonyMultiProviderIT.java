package br.com.gestorcobranca.telephony;

import br.com.gestorcobranca.collection.application.port.in.CaseUseCases.CaseView;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.telephony.application.port.in.AgentUseCases.ConfigureEndpointCommand;
import br.com.gestorcobranca.telephony.application.port.in.CallUseCases.CallView;
import br.com.gestorcobranca.telephony.application.port.in.CallUseCases.ClickToCallCommand;
import br.com.gestorcobranca.telephony.application.port.in.DialerCampaignUseCases.CampaignSettings;
import br.com.gestorcobranca.telephony.application.port.in.DialerCampaignUseCases.CampaignView;
import br.com.gestorcobranca.telephony.application.port.in.DialerCampaignUseCases.CreateCampaignCommand;
import br.com.gestorcobranca.telephony.application.port.in.TelephonyAccountUseCases.AccountComparison;
import br.com.gestorcobranca.telephony.application.port.in.TelephonyAccountUseCases.AccountView;
import br.com.gestorcobranca.telephony.application.port.in.TelephonyAccountUseCases.CreateAccountCommand;
import br.com.gestorcobranca.telephony.application.port.in.TelephonyAccountUseCases.ProviderView;
import br.com.gestorcobranca.telephony.application.port.in.TelephonyRoutingUseCases;
import br.com.gestorcobranca.telephony.application.port.in.TelephonyRoutingUseCases.RouteView;
import br.com.gestorcobranca.telephony.application.port.in.TelephonyRoutingUseCases.SaveRouteCommand;
import br.com.gestorcobranca.telephony.application.port.in.TelephonyWebhookUseCases;
import br.com.gestorcobranca.telephony.application.port.in.TelephonyWebhookUseCases.Outcome;
import br.com.gestorcobranca.telephony.infrastructure.provider.TimestampedHmac;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ambiente multiprovedor com dois provedores ao mesmo tempo: o simulador ({@code SANDBOX}) e a
 * Alvaria (contrato a confirmar, liberada para homologação no perfil de teste) contra um dublê HTTP
 * local — nenhuma chamada real. Roteamento por credor e por campanha, ramal por plataforma,
 * contingência sem chamada duplicada, eventos assinados por conta, comparação entre provedores e
 * Tarifando recusada.
 */
class TelephonyMultiProviderIT extends TelephonyTestSupport {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String ALVARIA_SECRET = "segredo-alvaria-teste";
    private static HttpServer alvaria;
    private static final Queue<JsonNode> received = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger status = new AtomicInteger(201);
    private static final AtomicInteger sequence = new AtomicInteger();
    private static final AtomicReference<String> lastPath = new AtomicReference<>();

    @Autowired
    TelephonyRoutingUseCases routing;
    @Autowired
    TelephonyWebhookUseCases webhooks;

    AccountView alvariaAccount;

    @BeforeAll
    static void startAlvaria() throws IOException {
        alvaria = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        alvaria.createContext("/", TelephonyMultiProviderIT::handle);
        alvaria.start();
    }

    @AfterAll
    static void stopAlvaria() {
        alvaria.stop(0);
    }

    private static void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String response;
        int code;
        if (path.endsWith("/oauth2/token")) {
            code = 200;
            response = "{\"access_token\":\"tok\",\"expires_in\":3600}";
        } else {
            lastPath.set(path);
            JsonNode node = MAPPER.readTree(body.isBlank() ? "{}" : body);
            code = status.get();
            if (code >= 300) {
                response = "{}";
            } else if (path.endsWith("/calls/batch")) {
                received.add(node);
                StringBuilder results = new StringBuilder("{\"results\":[");
                for (JsonNode call : node.path("calls")) {
                    if (results.charAt(results.length() - 1) == '}') {
                        results.append(',');
                    }
                    results.append("{\"clientReference\":\"").append(call.path("clientReference").asString())
                            .append("\",\"accepted\":true,\"callId\":\"alv-").append(sequence.incrementAndGet()).append("\"}");
                }
                response = results.append("]}").toString();
            } else {
                received.add(node);
                response = "{\"callId\":\"alv-" + sequence.incrementAndGet() + "\"}";
            }
        }
        byte[] out = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, out.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(out);
        }
    }

    @BeforeEach
    void setUp() {
        received.clear();
        status.set(201);
        provision("mpv", false);
        alvariaAccount = asAdmin(() -> accounts.create(new CreateAccountCommand("ALVARIA", null, "Alvaria CX",
                "+551140000000", false, false, Map.of("apiBaseUrl", "http://127.0.0.1:" + alvaria.getAddress().getPort(),
                "clientId", "cli", "clientSecret", "sec", "webhookSecret", ALVARIA_SECRET), new BigDecimal("0.0800"))));
    }

    private RouteView route(String scope, UUID scopeId, UUID primary, UUID fallback) {
        return asManager(() -> routing.save(new SaveRouteCommand(scope, scopeId, primary, fallback)));
    }

    private CallView clickToCall(UUID operator, UUID caseId) {
        return asOperator(operator, () -> calls.clickToCall(new ClickToCallCommand(caseId, null), null));
    }

    private Outcome alvariaEvent(String providerCallId, UUID reference, String type, String amd, String hangupBy) {
        String body = "{\"events\":[{\"id\":\"" + UUID.randomUUID() + "\",\"type\":\"" + type + "\",\"callId\":\""
                + providerCallId + "\",\"reference\":\"" + reference + "\",\"timestamp\":\"" + Instant.now() + "\""
                + (amd == null ? "" : ",\"amd\":\"" + amd + "\"")
                + (hangupBy == null ? "" : ",\"hangupBy\":\"" + hangupBy + "\"") + "}]}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String routingKey = alvariaAccount.webhookPath().substring(alvariaAccount.webhookPath().lastIndexOf('/') + 1);
        return webhooks.receive(routingKey, Map.of("x-gestor-signature",
                TimestampedHmac.header(ALVARIA_SECRET, Instant.now().getEpochSecond(), bytes)), bytes, "127.0.0.1");
    }

    private String providerCallId(UUID callId) {
        return string("select provider_call_id from telephony.phone_call where id = ?", callId);
    }

    @Test
    void bothProvidersAreActiveAndTheCreditorRuleSendsItsCallsThroughAlvaria() {
        assertThat(alvariaAccount.active()).isTrue();
        assertThat(alvariaAccount.readiness()).isEqualTo("CONTRACT_PENDING");
        assertThat(alvariaAccount.dialable()).isTrue();
        assertThat(asAdmin(accounts::list)).extracting(AccountView::provider).containsExactlyInAnyOrder("SANDBOX", "ALVARIA");
        // a primeira conta da assessoria virou o padrão; a segunda não muda o roteamento sozinha
        assertThat(asManager(routing::list)).singleElement().satisfies(r -> {
            assertThat(r.scopeType()).isEqualTo("DEFAULT");
            assertThat(r.primaryAccountId()).isEqualTo(account.id());
        });
        route("CREDITOR", creditorA, alvariaAccount.id(), null);
        asManager(() -> agents.configureEndpoint(operatorX, alvariaAccount.id(), new ConfigureEndpointCommand("alv-ramal-x",
                true)));
        CaseView caseA = loadCase(creditorA, portfolioA, debtor("529.982.247-25"), "900.00");
        CaseView caseB = loadCase(creditorB, portfolioB, debtor("111.444.777-35", "(11) 97777-1234"), "300.00");
        asOperator(operatorX, () -> agents.login(null));

        CallView viaAlvaria = clickToCall(operatorX, caseA.id());
        assertThat(viaAlvaria.provider()).isEqualTo("ALVARIA");
        drainTelephony();
        JsonNode originate = received.poll();
        assertThat(originate).isNotNull();
        assertThat(originate.path("agentEndpoint").asString()).isEqualTo("alv-ramal-x");
        assertThat(originate.path("clientReference").asString()).isEqualTo(viaAlvaria.id().toString());
        assertThat(originate.path("callerId").asString()).isEqualTo("+551140000000");
        assertThat(call(viaAlvaria.id()).status()).isEqualTo("DIALING");
        String alvariaId = providerCallId(viaAlvaria.id());

        assertThat(alvariaEvent(alvariaId, viaAlvaria.id(), "ANSWERED", "HUMAN", null)).isEqualTo(Outcome.ACCEPTED);
        assertThat(alvariaEvent(alvariaId, viaAlvaria.id(), "COMPLETED", null, "DEBTOR")).isEqualTo(Outcome.ACCEPTED);
        assertThat(call(viaAlvaria.id()).status()).isEqualTo("COMPLETED");
        assertThat(call(viaAlvaria.id()).result()).isEqualTo("ANSWERED");
        // evento assinado com o segredo de outra conta é recusado
        String routingKey = alvariaAccount.webhookPath().substring(alvariaAccount.webhookPath().lastIndexOf('/') + 1);
        byte[] forged = "{\"events\":[]}".getBytes(StandardCharsets.UTF_8);
        assertThat(webhooks.receive(routingKey, Map.of("x-gestor-signature", TimestampedHmac.header(WEBHOOK_SECRET,
                Instant.now().getEpochSecond(), forged)), forged, "127.0.0.1")).isEqualTo(Outcome.INVALID_SIGNATURE);

        asOperator(operatorX, () -> calls.tabulate(viaAlvaria.id(),
                new br.com.gestorcobranca.telephony.application.port.in.CallUseCases.TabulateCommand("CPC_NEGOTIATING",
                        null, null, null, null)));
        CallView viaSandbox = clickToCall(operatorX, caseB.id());
        assertThat(viaSandbox.provider()).isEqualTo("SANDBOX");
        drainTelephony();
        assertThat(received).isEmpty();
        assertThat(call(viaSandbox.id()).status()).isEqualTo("DIALING");

        List<AccountComparison> comparison = asManager(() -> accounts.comparison(LocalDate.now().minusDays(1),
                LocalDate.now().plusDays(1)));
        assertThat(comparison).filteredOn(c -> c.accountId().equals(alvariaAccount.id())).singleElement().satisfies(c -> {
            assertThat(c.dialed()).isEqualTo(1);
            assertThat(c.connected()).isEqualTo(1);
            assertThat(c.asr()).isEqualTo(1.0);
            assertThat(c.ratePerMinute()).isEqualByComparingTo("0.08");
            assertThat(c.estimatedCost()).isNotNull();
        });
        assertThat(comparison).filteredOn(c -> c.accountId().equals(account.id())).singleElement()
                .satisfies(c -> assertThat(c.dialed()).isEqualTo(1));
        assertThat(count("select count(*) from shared.business_event where event_type = 'call.dialed' "
                + "and attributes ->> 'provider' = 'ALVARIA'")).isEqualTo(1);
    }

    @Test
    void fallbackDialsTheSameCallOnceWhenThePrimaryDidNotCreateIt() {
        route("CREDITOR", creditorA, alvariaAccount.id(), account.id());
        CaseView kase = loadCase(creditorA, portfolioA, debtor("529.982.247-25"), "900.00");
        asOperator(operatorX, () -> agents.login(null));
        status.set(503);

        CallView queued = clickToCall(operatorX, kase.id());
        assertThat(queued.provider()).isEqualTo("ALVARIA");
        drainTelephony();

        CallView dialed = call(queued.id());
        assertThat(dialed.status()).isEqualTo("DIALING");
        assertThat(dialed.provider()).isEqualTo("SANDBOX");
        assertThat(dialed.accountId()).isEqualTo(account.id());
        assertThat(dialed.fallbackFromAccountId()).isEqualTo(alvariaAccount.id());
        assertThat(providerCallId(queued.id())).startsWith("sbx-call-");
        assertThat(count("select count(*) from compliance.contact_attempt where reference_id = ?", queued.id()))
                .isEqualTo(1);
        assertThat(count("select count(*) from telephony.phone_call where case_id = ?", kase.id())).isEqualTo(1);
    }

    @Test
    void operatorOffAPlatformIsRoutedToAnotherAccountOrRefused() {
        route("CREDITOR", creditorA, alvariaAccount.id(), account.id());
        asManager(() -> agents.configureEndpoint(operatorX, alvariaAccount.id(), new ConfigureEndpointCommand(null, false)));
        CaseView kase = loadCase(creditorA, portfolioA, debtor("529.982.247-25"), "900.00");
        asOperator(operatorX, () -> agents.login(null));

        CallView call = clickToCall(operatorX, kase.id());
        assertThat(call.provider()).isEqualTo("SANDBOX");
        drainTelephony();
        assertThat(received).isEmpty();

        route("CREDITOR", creditorA, alvariaAccount.id(), null);
        asOperator(operatorY, () -> agents.login(null));
        asManager(() -> agents.configureEndpoint(operatorY, alvariaAccount.id(), new ConfigureEndpointCommand(null, false)));
        assertThatThrownBy(() -> clickToCall(operatorY, kase.id())).isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", "telephony.agent_not_on_account");
        assertThat(asSupervisor(() -> agents.endpoints())).hasSize(2)
                .allSatisfy(e -> assertThat(e.active()).isFalse());
    }

    @Test
    void campaignRuleDialsTheBatchThroughAlvariaAndBridgesOnlyReachableOperators() {
        UUID queue = queueA("MULTI");
        CaseView kase = loadCase(creditorA, portfolioA, debtor("529.982.247-25"), "900.00");
        CampaignView campaign = asManager(() -> dialer.create(new CreateCampaignCommand("ALV", null, queue,
                new CampaignSettings("Campanha Alvaria", null, false, "PROGRESSIVE", null, null, 3, 30, 20, null, null))));
        route("CAMPAIGN", campaign.id(), alvariaAccount.id(), null);
        asManager(() -> agents.configureEndpoint(operatorY, alvariaAccount.id(), new ConfigureEndpointCommand(null, false)));
        asManager(() -> agents.configureEndpoint(operatorX, alvariaAccount.id(), new ConfigureEndpointCommand("alv-x", true)));
        asSupervisor(() -> dialer.activate(campaign.id()));
        assertThatThrownBy(() -> asOperator(operatorY, () -> agents.login(campaign.id())))
                .isInstanceOf(ConflictException.class).hasFieldOrPropertyWithValue("code", "telephony.agent_not_on_account");
        asOperator(operatorX, () -> agents.login(campaign.id()));
        drainTelephony();

        JsonNode batch = received.poll();
        assertThat(batch).isNotNull();
        assertThat(batch.path("calls")).hasSize(1);
        assertThat(batch.path("calls").path(0).path("answeringMachineDetection").asBoolean()).isTrue();
        UUID callId = UUID.fromString(batch.path("calls").path(0).path("clientReference").asString());
        assertThat(call(callId).caseId()).isEqualTo(kase.id());
        assertThat(call(callId).provider()).isEqualTo("ALVARIA");

        assertThat(alvariaEvent(providerCallId(callId), callId, "ANSWERED", "HUMAN", null)).isEqualTo(Outcome.ACCEPTED);
        JsonNode bridge = received.poll();
        assertThat(bridge).isNotNull();
        assertThat(bridge.path("agentEndpoint").asString()).isEqualTo("alv-x");
        assertThat(lastPath.get()).endsWith("/bridge");
        assertThat(call(callId).operatorId()).isEqualTo(operatorX);
    }

    @Test
    void providerCatalogAndRulesRefuseWhatCannotDial() {
        List<ProviderView> providers = asManager(accounts::providers);
        assertThat(providers).extracting(ProviderView::provider).containsExactly("SANDBOX", "ALVARIA", "TARIFANDO");
        assertThat(providers).filteredOn(p -> p.provider().equals("TARIFANDO")).singleElement().satisfies(p -> {
            assertThat(p.readiness()).isEqualTo("NOT_IMPLEMENTED");
            assertThat(p.available()).isFalse();
            assertThat(p.dialable()).isFalse();
        });
        assertThatThrownBy(() -> asAdmin(() -> accounts.create(new CreateAccountCommand("TARIFANDO", null, "Tarifando",
                "+554830364800", false, false, Map.of("apiBaseUrl", "https://api.tarifando.com.br", "apiToken", "t",
                "webhookSecret", "w"))))).isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", "telephony_account.provider_unavailable");
        assertThatThrownBy(() -> asAdmin(() -> accounts.create(new CreateAccountCommand("ALVARIA", null, "Fora do domínio",
                "+551140000000", false, false, Map.of("apiBaseUrl", "http://intranet.local", "clientId", "c",
                "clientSecret", "s", "webhookSecret", "w"))))).isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", "telephony_account.alvaria_host_not_allowed");

        AccountView ofB = asAdmin(() -> accounts.create(new CreateAccountCommand("SANDBOX", creditorB, "Bina B",
                "+551130000002", false, false, Map.of("webhookSecret", "b"))));
        assertThat(asManager(routing::list)).anySatisfy(r -> {
            assertThat(r.scopeType()).isEqualTo("CREDITOR");
            assertThat(r.scopeId()).isEqualTo(creditorB);
            assertThat(r.primaryAccountId()).isEqualTo(ofB.id());
        });
        assertThatThrownBy(() -> route("CREDITOR", creditorA, ofB.id(), null)).isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", "telephony_route.account_other_creditor");
        assertThatThrownBy(() -> route("DEFAULT", null, ofB.id(), null)).isInstanceOf(BusinessRuleException.class);

        RouteView portfolioRule = route("PORTFOLIO", portfolioA, alvariaAccount.id(), account.id());
        assertThat(portfolioRule.creditorId()).isEqualTo(creditorA);
        asManager(() -> {
            routing.delete(portfolioRule.id());
            return null;
        });
        assertThat(count("select count(*) from shared.audit_event where action like 'telephony_route.%'"))
                .isGreaterThanOrEqualTo(3);

        asAdmin(() -> accounts.deactivate(alvariaAccount.id()));
        route("CREDITOR", creditorA, alvariaAccount.id(), null);
        CaseView kase = loadCase(creditorA, portfolioA, debtor("529.982.247-25"), "900.00");
        asOperator(operatorX, () -> agents.login(null));
        // conta inativa na regra do credor: vale a regra seguinte (padrão da assessoria)
        assertThat(clickToCall(operatorX, kase.id()).provider()).isEqualTo("SANDBOX");
    }
}
