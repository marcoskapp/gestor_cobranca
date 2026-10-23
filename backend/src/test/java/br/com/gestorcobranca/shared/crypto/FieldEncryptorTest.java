package br.com.gestorcobranca.shared.crypto;

import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FieldEncryptorTest {

    private static final String K1 = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());
    private static final String K2 = Base64.getEncoder().encodeToString("fedcba9876543210fedcba9876543210".getBytes());
    private static final String BLIND = Base64.getEncoder().encodeToString("blind-index-key-with-32-bytes!!!".getBytes());

    @Test
    void roundTripsWithRandomIv() {
        FieldEncryptor encryptor = new FieldEncryptor(new CryptoProperties(Map.of("k1", K1), "k1", BLIND));
        String a = encryptor.encrypt("529.982.247-25");
        String b = encryptor.encrypt("529.982.247-25");
        assertThat(a).isNotEqualTo(b).startsWith("k1:").doesNotContain("529.982.247-25");
        assertThat(encryptor.decrypt(a)).isEqualTo("529.982.247-25");
    }

    @Test
    void readsDataWrittenWithPreviousKeyAfterRotation() {
        FieldEncryptor old = new FieldEncryptor(new CryptoProperties(Map.of("k1", K1), "k1", BLIND));
        String stored = old.encrypt("segredo");
        FieldEncryptor rotated = new FieldEncryptor(new CryptoProperties(Map.of("k1", K1, "k2", K2), "k2", BLIND));
        assertThat(rotated.decrypt(stored)).isEqualTo("segredo");
        assertThat(rotated.needsRotation(stored)).isTrue();
        assertThat(rotated.encrypt("x")).startsWith("k2:");
    }

    @Test
    void detectsTampering() {
        FieldEncryptor encryptor = new FieldEncryptor(new CryptoProperties(Map.of("k1", K1), "k1", BLIND));
        String stored = encryptor.encrypt("valor");
        char[] chars = stored.toCharArray();
        chars[chars.length - 3] = chars[chars.length - 3] == 'A' ? 'B' : 'A';
        assertThatThrownBy(() -> encryptor.decrypt(new String(chars))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void blindIndexIsDeterministicPerTenantAndPurpose() {
        BlindIndex index = new BlindIndex(new CryptoProperties(Map.of("k1", K1), "k1", BLIND));
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        assertThat(index.of(tenantA, "debtor.document", "52998224725"))
                .isEqualTo(index.of(tenantA, "debtor.document", "52998224725"))
                .isNotEqualTo(index.of(tenantB, "debtor.document", "52998224725"))
                .isNotEqualTo(index.of(tenantA, "contact.phone", "52998224725"));
    }
}
