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
        assertThat(a).isNotEqualTo(b).startsWith("k1v2:").doesNotContain("529.982.247-25");
        assertThat(encryptor.decrypt(a)).isEqualTo("529.982.247-25");
    }

    @Test
    void readsDataWrittenWithPreviousKeyAfterRotation() {
        FieldEncryptor old = new FieldEncryptor(new CryptoProperties(Map.of("k1", K1), "k1", BLIND));
        String stored = old.encrypt("segredo");
        FieldEncryptor rotated = new FieldEncryptor(new CryptoProperties(Map.of("k1", K1, "k2", K2), "k2", BLIND));
        assertThat(rotated.decrypt(stored)).isEqualTo("segredo");
        assertThat(rotated.needsRotation(stored)).isTrue();
        assertThat(rotated.encrypt("x")).startsWith("k2v2:");
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
    void valueIsBoundToTheTenantItWasWrittenFor() {
        FieldEncryptor encryptor = new FieldEncryptor(new CryptoProperties(Map.of("k1", K1), "k1", BLIND));
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        String stored = inTenant(tenantA, () -> encryptor.encrypt("11999990000"));

        assertThat(inTenant(tenantA, () -> encryptor.decrypt(stored))).isEqualTo("11999990000");
        // copiado para um registro de outra assessoria: não decifra
        assertThatThrownBy(() -> inTenant(tenantB, () -> encryptor.decrypt(stored))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> encryptor.decrypt(stored)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void legacyFormatWithoutTenantStaysReadableAndIsFlaggedForRotation() throws Exception {
        FieldEncryptor encryptor = new FieldEncryptor(new CryptoProperties(Map.of("k1", K1), "k1", BLIND));
        // formato anterior: AAD só com o id da chave
        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        byte[] iv = new byte[12];
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, new javax.crypto.spec.SecretKeySpec(Base64.getDecoder().decode(K1), "AES"),
                new javax.crypto.spec.GCMParameterSpec(128, iv));
        cipher.updateAAD("k1".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] encrypted = cipher.doFinal("antigo".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] payload = java.nio.ByteBuffer.allocate(12 + encrypted.length).put(iv).put(encrypted).array();
        String legacy = "k1:" + Base64.getEncoder().encodeToString(payload);

        assertThat(inTenant(UUID.randomUUID(), () -> encryptor.decrypt(legacy))).isEqualTo("antigo");
        assertThat(encryptor.needsRotation(legacy)).isTrue();
        assertThat(encryptor.needsRotation(encryptor.encrypt("novo"))).isFalse();
    }

    @Test
    void keyIdsThatCollideWithTheVersionSuffixAreRejected() {
        assertThatThrownBy(() -> new FieldEncryptor(new CryptoProperties(Map.of("k1v2", K1), "k1v2", BLIND)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new FieldEncryptor(new CryptoProperties(Map.of("K:1", K1), "K:1", BLIND)))
                .isInstanceOf(IllegalStateException.class);
    }

    private static <T> T inTenant(UUID tenantId, java.util.function.Supplier<T> action) {
        var context = org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
        context.setAuthentication(br.com.gestorcobranca.shared.security.ActorAuthentication.of(
                br.com.gestorcobranca.shared.security.Actor.system(tenantId, "teste")));
        var previous = org.springframework.security.core.context.SecurityContextHolder.getContext();
        org.springframework.security.core.context.SecurityContextHolder.setContext(context);
        try {
            return action.get();
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.setContext(previous);
        }
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
