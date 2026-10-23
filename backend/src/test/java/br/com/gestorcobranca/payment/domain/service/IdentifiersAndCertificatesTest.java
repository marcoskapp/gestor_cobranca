package br.com.gestorcobranca.payment.domain.service;

import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Money;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Identificadores Pix, impressão digital de extrato e leitura do certificado de cliente (PKCS#12 de teste). */
class IdentifiersAndCertificatesTest {

    @Test
    void pixIdentifiers() {
        String txid = PixIdentifiers.txidFor(UUID.randomUUID());
        assertThat(txid).hasSize(32);
        assertThat(PixIdentifiers.validTxid(txid)).isTrue();
        assertThat(PixIdentifiers.validTxid("curto")).isFalse();
        assertThat(PixIdentifiers.validTxid("com-hifen-" + "a".repeat(20))).isFalse();
        assertThat(PixIdentifiers.validEndToEndId("E" + "a".repeat(31))).isTrue();
        assertThat(PixIdentifiers.validEndToEndId("E" + "a".repeat(30))).isFalse();
    }

    @Test
    void fingerprintIdentifiesTheSameTransactionAcrossFiles() {
        LocalDate day = LocalDate.of(2026, 10, 20);
        String byTx = StatementFingerprint.of(day, Money.of("10.00"), true, "e2e123", null, 1);
        assertThat(StatementFingerprint.of(day.plusDays(1), Money.of("99.00"), true, "E2E123", "x", 7)).isEqualTo(byTx);
        assertThat(StatementFingerprint.of(day, Money.of("10.00"), false, "E2E123", null, 1)).isNotEqualTo(byTx);
        String first = StatementFingerprint.of(day, Money.of("10.00"), true, null, "REF", 1);
        assertThat(StatementFingerprint.of(day, Money.of("10.00"), true, null, "ref", 1)).isEqualTo(first);
        assertThat(StatementFingerprint.of(day, Money.of("10.00"), true, null, "REF", 2)).isNotEqualTo(first);
    }

    @Test
    void clientCertificateMustOpenWithPasswordAndBeValid() throws Exception {
        byte[] p12;
        try (InputStream in = getClass().getResourceAsStream("/payment/psp-client-test.p12")) {
            p12 = in.readAllBytes();
        }
        ClientCertificates.Info info = ClientCertificates.inspect(p12, "test-only-pass", Instant.now());
        assertThat(info.sha256()).hasSize(64);
        assertThat(info.notAfter()).isAfter(Instant.now());
        assertThatThrownBy(() -> ClientCertificates.inspect(p12, "senha-errada", Instant.now()))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("senha");
        assertThatThrownBy(() -> ClientCertificates.inspect(p12, "test-only-pass", info.notAfter().plusSeconds(60)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("vencido");
        assertThatThrownBy(() -> ClientCertificates.inspect(new byte[0], "x", Instant.now()))
                .isInstanceOf(BusinessRuleException.class);
    }
}
