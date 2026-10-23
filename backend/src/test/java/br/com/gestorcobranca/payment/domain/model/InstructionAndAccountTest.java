package br.com.gestorcobranca.payment.domain.model;

import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.DomainException;
import br.com.gestorcobranca.shared.domain.Money;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InstructionAndAccountTest {

    private final Instant now = Instant.parse("2026-10-23T12:00:00Z");

    private PaymentInstruction pix() {
        return PaymentInstruction.forTitle(InstructionType.PIX_IMMEDIATE, UUID.randomUUID(), null, UUID.randomUUID(),
                UUID.randomUUID(), Money.of("10.00"), null, now.plusSeconds(3600), UUID.randomUUID(), "a".repeat(32), null,
                now);
    }

    @Test
    void instructionLifecycle() {
        PaymentInstruction i = pix();
        assertThat(i.getStatus()).isEqualTo(InstructionStatus.PENDING);
        i.activate(0, "loc", "000201...", now);
        assertThat(i.getStatus()).isEqualTo(InstructionStatus.ACTIVE);
        assertThatThrownBy(() -> i.activate(1, null, "x", now)).isInstanceOf(DomainException.class);
        assertThat(i.expireIfDue(now)).isFalse();
        assertThat(i.markPaid(UUID.randomUUID(), now)).isTrue();
        assertThat(i.markPaid(UUID.randomUUID(), now)).isFalse();
        assertThatThrownBy(() -> i.cancel("x", now)).isInstanceOf(DomainException.class);

        PaymentInstruction expiring = pix();
        assertThat(expiring.expireIfDue(now.plusSeconds(3600))).isTrue();
        assertThat(expiring.getStatus()).isEqualTo(InstructionStatus.EXPIRED);

        PaymentInstruction cancelled = pix();
        assertThat(cancelled.cancel("Acordo encerrado", now)).isFalse();
        assertThat(cancelled.getStatus()).isEqualTo(InstructionStatus.CANCELLED);
        assertThatThrownBy(() -> PaymentInstruction.forTitle(InstructionType.PIX_DUE, UUID.randomUUID(), null,
                UUID.randomUUID(), UUID.randomUUID(), Money.of("10.00"), null, now.plusSeconds(60), UUID.randomUUID(),
                "a".repeat(32), null, now)).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> PaymentInstruction.forTitle(InstructionType.PIX_DUE, UUID.randomUUID(), null,
                UUID.randomUUID(), UUID.randomUUID(), Money.of("10.00"), LocalDate.of(2026, 11, 1), now, UUID.randomUUID(),
                "a".repeat(32), null, now)).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void pixAccountRequiresHttpsAndPinnedCertificateForMtls() {
        String fingerprint = "AB:".repeat(31) + "AB";
        PixAccount account = new PixAccount(null, "PSP", PixProviderType.BACEN_API, "chave@psp", "https://psp.example/api/v2/",
                "https://psp.example/oauth/token", WebhookAuthMode.MTLS, List.of(fingerprint), "k".repeat(43));
        assertThat(account.getApiBaseUrl()).isEqualTo("https://psp.example/api/v2");
        assertThat(account.webhookFingerprints()).containsExactly("ab".repeat(32));
        assertThatThrownBy(() -> new PixAccount(null, "PSP", PixProviderType.BACEN_API, "chave", "http://psp.example",
                "https://psp.example/token", WebhookAuthMode.HMAC, List.of(), "k".repeat(43)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("HTTPS");
        assertThatThrownBy(() -> new PixAccount(null, "PSP", PixProviderType.BACEN_API, "chave", "https://user:pw@psp.example",
                "https://psp.example/token", WebhookAuthMode.HMAC, List.of(), "k".repeat(43)))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> account.configureWebhook(WebhookAuthMode.MTLS, List.of()))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> account.configureWebhook(WebhookAuthMode.MTLS, List.of("nao-hex")))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void divergenceResolutionRequiresJustificationAndHappensOnce() {
        Divergence d = new Divergence(DivergenceType.UNMATCHED, DivergenceSource.STATEMENT, null, null, null, null, null,
                Money.of("1.00"), null, LocalDate.of(2026, 10, 1));
        assertThatThrownBy(() -> d.resolve(DivergenceResolution.MATCHED_PAYMENT, null, "x", UUID.randomUUID(), now))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> d.resolve(DivergenceResolution.DISMISSED, null, " ", UUID.randomUUID(), now))
                .isInstanceOf(BusinessRuleException.class);
        d.resolve(DivergenceResolution.DISMISSED, null, "Outra operação", UUID.randomUUID(), now);
        assertThat(d.getStatus()).isEqualTo(DivergenceStatus.DISMISSED);
        assertThatThrownBy(() -> d.resolve(DivergenceResolution.ACCEPTED, null, "x", UUID.randomUUID(), now))
                .isInstanceOf(DomainException.class);
    }
}
