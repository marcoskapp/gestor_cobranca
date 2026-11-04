package br.com.gestorcobranca.identity.application.service;

import br.com.gestorcobranca.shared.events.publicapi.PlatformTokensControlled;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Texto do aviso à assessoria: tokens pelo prefixo público, retomada agendada e sem o motivo. */
class PlatformTokenNoticeListenerTest {

    private static PlatformTokensControlled event(String action, int tokens, Instant resumeAt) {
        List<UUID> ids = java.util.stream.IntStream.range(0, tokens).mapToObj(i -> UUID.randomUUID()).toList();
        List<String> prefixes = java.util.stream.IntStream.range(0, tokens).mapToObj(i -> "gck_abcdefghij" + (char) ('a' + i % 26) + i)
                .toList();
        return new PlatformTokensControlled(UUID.randomUUID(), UUID.randomUUID(), Instant.now(), action, ids, prefixes,
                resumeAt);
    }

    @Test
    void pausedNoticeNamesTheTokenAndTheScheduledResume() {
        PlatformTokensControlled e = event(PlatformTokensControlled.PAUSED, 1, Instant.parse("2026-11-05T15:00:00Z"));
        assertThat(PlatformTokenNoticeListener.subject(e)).isEqualTo("Token de integração pausado pela plataforma");
        assertThat(PlatformTokenNoticeListener.message(e)).contains(e.tokenPrefixes().getFirst())
                .contains("05/11/2026 12:00").contains("API pública → Tokens de integração");
    }

    @Test
    void bulkNoticeListsAtMostTenPrefixesAndEveryActionHasItsText() {
        PlatformTokensControlled bulk = event(PlatformTokensControlled.PAUSED, 12, null);
        assertThat(PlatformTokenNoticeListener.subject(bulk)).isEqualTo("Tokens de integração pausados pela plataforma");
        assertThat(PlatformTokenNoticeListener.message(bulk)).contains("e mais 2").contains("até a plataforma retomar")
                .doesNotContain(bulk.tokenPrefixes().get(11));
        assertThat(PlatformTokenNoticeListener.message(event(PlatformTokensControlled.RESUMED, 1, null)))
                .contains("foi retomado pela equipe da plataforma");
        assertThat(PlatformTokenNoticeListener.message(event(PlatformTokensControlled.AUTO_RESUMED, 2, null)))
                .contains("foram retomados automaticamente");
        assertThat(PlatformTokenNoticeListener.subject(event(PlatformTokensControlled.REVOKED, 1, null)))
                .isEqualTo("Token de integração revogado pela plataforma");
    }
}
