package br.com.gestorcobranca.shared.crypto;

import br.com.gestorcobranca.shared.crypto.ExternalIdDigest.Kind;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Forma anonimizada do identificador do título e do contrato (decisão 1476). */
class ExternalIdDigestTest {

    private static final String K1 = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());
    private static final String BLIND = Base64.getEncoder().encodeToString("blind-index-key-with-32-bytes!!!".getBytes());
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID CREDITOR = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

    private final ExternalIdDigest digest = new ExternalIdDigest(new BlindIndex(new CryptoProperties(Map.of("k1", K1), "k1", BLIND)));

    @Test
    void formatIsPinnedAndDeterministic() {
        String a = digest.anonymized(TENANT, Kind.TITLE, CREDITOR, "52998224725-001");
        assertThat(a).matches("anon:[0-9a-f]{64}").hasSize(69);
        assertThat(ExternalIdDigest.isAnonymized(a)).isTrue();
        assertThat(ExternalIdDigest.keyOfAnonymized(a)).isEqualTo(digest.key(TENANT, Kind.TITLE, CREDITOR, "52998224725-001"));
        // canônico como a carga grava: sem espaços nas pontas
        assertThat(digest.anonymized(TENANT, Kind.TITLE, CREDITOR, " 52998224725-001 ")).isEqualTo(a);
    }

    @Test
    void creditorTenantAndPurposeChangeTheValue() {
        String a = digest.key(TENANT, Kind.TITLE, CREDITOR, "X-1");
        assertThat(digest.key(TENANT, Kind.TITLE, UUID.randomUUID(), "X-1")).isNotEqualTo(a);
        assertThat(digest.key(UUID.randomUUID(), Kind.TITLE, CREDITOR, "X-1")).isNotEqualTo(a);
        assertThat(digest.key(TENANT, Kind.CONTRACT, CREDITOR, "X-1")).isNotEqualTo(a);
    }

    @Test
    void candidatesAreAlwaysBothFormsAndNeverForTheReservedPrefix() {
        assertThat(digest.candidates(TENANT, Kind.TITLE, CREDITOR, " X-1 "))
                .containsExactly("X-1", digest.anonymized(TENANT, Kind.TITLE, CREDITOR, "X-1"));
        String anon = digest.anonymized(TENANT, Kind.TITLE, CREDITOR, "X-1");
        assertThat(digest.candidates(TENANT, Kind.TITLE, CREDITOR, anon)).isEmpty();
        assertThat(digest.candidates(TENANT, Kind.TITLE, CREDITOR, "  ")).isEmpty();
    }

    @Test
    void reservedPrefixIgnoresCaseAndSpaces() {
        assertThat(ExternalIdDigest.isReserved(" ANON:abc")).isTrue();
        assertThat(ExternalIdDigest.isReserved("anon:")).isTrue();
        assertThat(ExternalIdDigest.isReserved("anonimo-1")).isFalse();
        assertThat(ExternalIdDigest.isReserved(null)).isFalse();
        assertThat(ExternalIdDigest.isAnonymized("anon:XYZ")).isFalse();
    }

    @Test
    void controlCharactersAreNotCanonicalizedAwayAndUnicodeSpacesAre() {
        assertThat(ExternalIdDigest.hasControl("\u0001")).isTrue();
        assertThat(ExternalIdDigest.canonical("\u0001")).isEqualTo("\u0001");
        assertThat(digest.key(TENANT, Kind.TITLE, CREDITOR, "\u0001")).isNotNull();
        // a mesma forma canônica na conferência do prefixo e na busca
        assertThat(ExternalIdDigest.isReserved("\u2003anon:x")).isTrue();
        assertThat(ExternalIdDigest.canonical("\u2003X-1\u3000")).isEqualTo("X-1");
    }
}
