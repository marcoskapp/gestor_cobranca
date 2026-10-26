package br.com.gestorcobranca.payment;

import br.com.gestorcobranca.identity.domain.model.DefaultRoles;
import br.com.gestorcobranca.payment.application.port.in.PixAccountUseCases;
import br.com.gestorcobranca.payment.application.port.in.PixAccountUseCases.AccountView;
import br.com.gestorcobranca.payment.application.port.in.PixAccountUseCases.CreateAccountCommand;
import br.com.gestorcobranca.payment.application.port.in.PixAccountUseCases.CredentialsCommand;
import br.com.gestorcobranca.payment.application.port.in.PixAccountUseCases.UpdateAccountCommand;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.security.CreditorScope;
import br.com.gestorcobranca.shared.security.ScopeAccessDeniedException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Endereços de PSP definidos pela assessoria (revisão de segurança A9 e S1): troca de URL desativa a conta
 * até outra pessoa reativá-la e gera alerta; IPs e nomes internos são recusados; webhook mTLS só com o
 * balanceador verificando certificados.
 */
class PixAccountEndpointIT extends PaymentTestSupport {

    @Test
    void changingThePspAddressDeactivatesTheAccountUntilAnotherPersonReactivatesIt() {
        provision("pspurl");
        UUID id = pixAccount.id();
        assertThat(pixAccount.active()).isTrue();

        // só o nome: nada muda
        assertThat(asAdmin(() -> pixAccounts.update(id, new UpdateAccountCommand("PSP renomeado", pixAccount.pixKey(),
                pixAccount.apiBaseUrl(), pixAccount.tokenUrl(), "HMAC", List.of()))).active()).isTrue();

        AccountView changed = asAdmin(() -> pixAccounts.update(id, new UpdateAccountCommand("PSP renomeado",
                pixAccount.pixKey(), "https://outro-psp.sandbox.local/api/v2", pixAccount.tokenUrl(), "HMAC", List.of())));
        assertThat(changed.active()).isFalse();
        assertThat(count("select count(*) from payment.webhook_route where pix_account_id = ? and active", id)).isZero();
        assertThat(count("""
                select count(*) from shared.access_log where event_type = 'INTEGRATION_ENDPOINT_CHANGED'
                   and details->>'action' = 'changed' and details->>'pixAccountId' = ?""", id.toString())).isEqualTo(1);

        // quem trocou não reativa; outra pessoa com INTEGRATION_MANAGE sim
        assertThatThrownBy(() -> asAdmin(() -> pixAccounts.activate(id)))
                .isInstanceOf(ConflictException.class).hasMessageContaining("outra pessoa");
        assertThat(as(Ids.newId(), DefaultRoles.ADMIN, CreditorScope.ALL, () -> pixAccounts.activate(id)).active()).isTrue();
        // aprovada a troca, o próprio responsável volta a poder desativar e reativar
        asAdmin(() -> pixAccounts.deactivate(id));
        assertThat(asAdmin(() -> pixAccounts.activate(id)).active()).isTrue();
    }

    @Test
    void internalAddressesAndUnverifiedMtlsAreRefused() {
        provision("pspint");
        for (String url : List.of("https://169.254.169.254/latest", "https://10.0.0.5/api", "https://localhost/api",
                "https://metadata.google.internal/api", "https://[::1]/api")) {
            assertThatThrownBy(() -> asAdmin(() -> pixAccounts.create(new CreateAccountCommand(creditorA, "PSP", "BACEN_API",
                    "pix@assessoria.test", url, "https://psp.exemplo.com.br/oauth/token", "HMAC", List.of(),
                    new CredentialsCommand("client", "secret", WEBHOOK_SECRET, null, null)))))
                    .as(url).isInstanceOf(BusinessRuleException.class)
                    .satisfies(e -> assertThat(((BusinessRuleException) e).code()).isEqualTo("pix_account.host_not_allowed"));
        }
        assertThatThrownBy(() -> asAdmin(() -> pixAccounts.create(new CreateAccountCommand(creditorA, "PSP", "SANDBOX",
                "pix@assessoria.test", "https://psp.sandbox.local/api/v2", "https://psp.sandbox.local/oauth/token", "MTLS",
                List.of("a".repeat(64)), new CredentialsCommand("client", "secret", null, null, null)))))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(e -> assertThat(((BusinessRuleException) e).code()).isEqualTo("pix_account.mtls_unavailable"));
        // a tela sabe que o mTLS está indisponível e sugere HMAC (decisão 522)
        assertThat(asAdmin(() -> pixAccounts.webhookOptions()))
                .isEqualTo(new PixAccountUseCases.WebhookOptions(false, "HMAC"));
        assertThatThrownBy(() -> as(Ids.newId(), DefaultRoles.OPERADOR, CreditorScope.ALL, () -> pixAccounts.webhookOptions()))
                .isInstanceOf(ScopeAccessDeniedException.class);
    }
}
