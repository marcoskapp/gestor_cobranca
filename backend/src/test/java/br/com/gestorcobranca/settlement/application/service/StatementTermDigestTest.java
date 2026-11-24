package br.com.gestorcobranca.settlement.application.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** O hash do termo do portal do credor cobre a observação e cada campo da decisão (achado SEG-20261124-F03). */
class StatementTermDigestTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final UUID STATEMENT = UUID.fromString("00000000-0000-7000-8000-000000000002");
    private static final UUID PERSON = UUID.fromString("00000000-0000-7000-8000-000000000003");
    private static final UUID SESSION = UUID.fromString("00000000-0000-7000-8000-000000000004");
    private static final Instant AT = Instant.parse("2026-11-24T16:00:00.123456Z");

    private static String digest(String decision, String note, String net, List<String> files) {
        return StatementTermDigest.sha256(StatementTermDigest.canonical(TENANT, STATEMENT, decision, PERSON, SESSION, AT,
                AT, new BigDecimal(net), note, files));
    }

    @Test
    void observacaoDiferenteMudaOHashDoTermo() {
        List<String> files = List.of("a".repeat(64));
        String base = digest("CONTESTED", "Faltou o pagamento do dia 3", "820.00", files);
        assertThat(base).hasSize(64).isEqualTo(digest("CONTESTED", "Faltou o pagamento do dia 3", "820.00", files));
        assertThat(digest("CONTESTED", "Faltou o pagamento do dia 4", "820.00", files)).isNotEqualTo(base);
        assertThat(digest("CONTESTED", null, "820.00", files)).isNotEqualTo(base);
    }

    @Test
    void decisaoValorEArquivosEntramNoHash() {
        List<String> files = List.of("a".repeat(64));
        String base = digest("ACKNOWLEDGED", null, "820.00", files);
        assertThat(digest("CONTESTED", null, "820.00", files)).isNotEqualTo(base);
        assertThat(digest("ACKNOWLEDGED", null, "820.01", files)).isNotEqualTo(base);
        assertThat(digest("ACKNOWLEDGED", null, "820.00", List.of("b".repeat(64)))).isNotEqualTo(base);
    }

    /** Termos já gravados só continuam verificáveis se o formato v1 não mudar (mudança = versão nova). */
    @Test
    void formatoV1Congelado() {
        String canonical = StatementTermDigest.canonical(TENANT, STATEMENT, "ACKNOWLEDGED", PERSON, SESSION, AT, null,
                new BigDecimal("820.00"), null, List.of("a".repeat(64), "b".repeat(64)));
        assertThat(canonical).isEqualTo("v1|" + TENANT + "|" + STATEMENT + "|ACKNOWLEDGED|" + PERSON + "|" + SESSION
                + "|2026-11-24T16:00:00.123456Z|null|820.00|-|" + "a".repeat(64) + "," + "b".repeat(64));
        assertThat(StatementTermDigest.canonical(TENANT, STATEMENT, "CONTESTED", PERSON, SESSION, AT, AT,
                new BigDecimal("820.00"), "abc", List.of())).endsWith(
                "|2026-11-24T16:00:00.123456Z|820.00|ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad|");
    }
}
