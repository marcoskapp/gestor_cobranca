package br.com.gestorcobranca.telephony.domain.model;

import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Regras de domínio do ambiente multiprovedor: contas, regras de roteamento, ramais e contingência. */
class MultiProviderModelTest {

    private static final Instant T0 = Instant.parse("2026-10-26T13:00:00Z");

    private static TelephonyAccount account(ProviderType provider, UUID creditorId, boolean recording) {
        return new TelephonyAccount(provider, creditorId, provider.label(), "+551130000000", recording, recording, null,
                "k".repeat(43), true, T0);
    }

    @Test
    void fallbackSwitchesTheQueuedCallOnceWithTheFallbackRecordingSettings() {
        TelephonyAccount primary = account(ProviderType.SANDBOX, null, false);
        TelephonyAccount fallback = account(ProviderType.ALVARIA, null, true);
        Call call = Call.outbound(CallOrigin.CLICK_TO_CALL, primary, CallTest.target(), UUID.randomUUID(), null, null, 1, T0);

        call.switchToFallback(fallback);

        assertThat(call.getAccountId()).isEqualTo(fallback.getId());
        assertThat(call.getProvider()).isEqualTo(ProviderType.ALVARIA);
        assertThat(call.getFallbackFromAccountId()).isEqualTo(primary.getId());
        assertThat(call.isRecordingRequested()).isTrue();
        assertThat(call.isRecordingAnnounced()).isTrue();
        assertThatThrownBy(() -> call.switchToFallback(primary)).isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", "call.fallback_already_used");
    }

    @Test
    void fallbackIsRefusedAfterTheProviderAcceptedTheCall() {
        TelephonyAccount primary = account(ProviderType.SANDBOX, null, false);
        Call call = Call.outbound(CallOrigin.CLICK_TO_CALL, primary, CallTest.target(), UUID.randomUUID(), null, null, 1, T0);
        call.dialing("prov-1", UUID.randomUUID(), T0);

        assertThatThrownBy(() -> call.switchToFallback(account(ProviderType.ALVARIA, null, false)))
                .isInstanceOf(ConflictException.class).hasFieldOrPropertyWithValue("code", "call.fallback_not_queued");
        assertThatThrownBy(() -> Call.outbound(CallOrigin.CLICK_TO_CALL, primary, CallTest.target(), UUID.randomUUID(),
                null, null, 1, T0).switchToFallback(primary)).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void routeAcceptsOnlyAccountsOfTheScopeCreditorAndADistinctFallback() {
        UUID creditorA = UUID.randomUUID();
        TelephonyAccount shared = account(ProviderType.SANDBOX, null, false);
        TelephonyAccount ofA = account(ProviderType.ALVARIA, creditorA, false);
        TelephonyAccount ofB = account(ProviderType.ALVARIA, UUID.randomUUID(), false);

        AccountRoute route = new AccountRoute(RouteScope.CREDITOR, creditorA, creditorA, ofA, shared);
        assertThat(route.getPrimaryAccountId()).isEqualTo(ofA.getId());
        assertThat(route.getFallbackAccountId()).isEqualTo(shared.getId());

        assertThatThrownBy(() -> route.assign(ofB, null)).isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", "telephony_route.account_other_creditor");
        assertThatThrownBy(() -> route.assign(shared, shared)).isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", "telephony_route.fallback_same");
        assertThatThrownBy(() -> new AccountRoute(RouteScope.DEFAULT, null, null, ofA, null))
                .isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", "telephony_route.account_other_creditor");
        assertThatThrownBy(() -> new AccountRoute(RouteScope.PORTFOLIO, null, creditorA, ofA, null))
                .isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", "telephony_route.scope_id_invalid");
    }

    @Test
    void endpointOffThePlatformHasNoExtensionAndActiveEndpointRequiresOne() {
        AgentEndpoint off = new AgentEndpoint(UUID.randomUUID(), UUID.randomUUID(), "2001", false);
        assertThat(off.isActive()).isFalse();
        assertThat(off.getExtension()).isNull();

        AgentEndpoint on = new AgentEndpoint(UUID.randomUUID(), UUID.randomUUID(), " sip:op1@pabx ", true);
        assertThat(on.getExtension()).isEqualTo("sip:op1@pabx");
        assertThatThrownBy(() -> on.change(" ", true)).isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", "agent_endpoint.extension_required");
        assertThatThrownBy(() -> on.change("ramal com espaço", true)).isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", "agent.extension_invalid");
    }

    @Test
    void ratePerMinuteIsOptionalAndBounded() {
        TelephonyAccount account = account(ProviderType.SANDBOX, null, false);
        account.changeRate(new BigDecimal("0.0890"));
        assertThat(account.getRatePerMinute()).isEqualByComparingTo("0.089");
        account.changeRate(null);
        assertThat(account.getRatePerMinute()).isNull();
        assertThatThrownBy(() -> account.changeRate(new BigDecimal("-0.01"))).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> account.changeRate(new BigDecimal("0.00001"))).isInstanceOf(BusinessRuleException.class);
    }
}
