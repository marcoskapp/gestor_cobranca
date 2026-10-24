package br.com.gestorcobranca.portal.domain.model;

import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PortalDomainTest {

    static final Instant T0 = Instant.parse("2026-10-23T12:00:00Z");
    static final String NOTICE = "Aviso de privacidade com texto suficiente para o mínimo exigido pela configuração.";

    static PortalConfiguration.Settings settings(String notice) {
        return new PortalConfiguration.Settings("banco-a", true, "Banco A", "#1D4E89", "#0B6E4F", Set.of(),
                Set.of(PortalChannel.WHATSAPP), KnowledgeChallenge.NONE, 48, notice, null);
    }

    @Test
    void decoyChallengeNeverVerifiesAndAttemptsAreBounded() {
        AccessChallenge decoy = AccessChallenge.otp(UUID.randomUUID(), null, PortalChannel.WHATSAPP, "d".repeat(64),
                "c".repeat(64), KnowledgeChallenge.NONE, 5, T0.plusSeconds(600));
        assertThat(decoy.isDecoy()).isTrue();
        assertThatThrownBy(() -> decoy.verify(T0)).isInstanceOf(ConflictException.class);
        AccessChallenge real = AccessChallenge.otp(UUID.randomUUID(), UUID.randomUUID(), PortalChannel.WHATSAPP,
                "d".repeat(64), "c".repeat(64), KnowledgeChallenge.NONE, 3, T0.plusSeconds(600));
        assertThat(real.codeMatches("c".repeat(64))).isTrue();
        assertThat(real.codeMatches("e".repeat(64))).isFalse();
        real.fail(T0);
        real.fail(T0);
        assertThat(real.openAt(T0)).isTrue();
        real.fail(T0);
        assertThat(real.getStatus()).isEqualTo(ChallengeStatus.FAILED);
        assertThat(real.openAt(T0)).isFalse();
        AccessChallenge expiring = AccessChallenge.otp(UUID.randomUUID(), UUID.randomUUID(), PortalChannel.SMS,
                "d".repeat(64), "c".repeat(64), KnowledgeChallenge.NONE, 3, T0.plusSeconds(1));
        expiring.expireIfDue(T0.plusSeconds(2));
        assertThat(expiring.getStatus()).isEqualTo(ChallengeStatus.EXPIRED);
    }

    @Test
    void linkIsSingleUse() {
        AccessLink link = new AccessLink(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "a".repeat(64),
                PortalChannel.WHATSAPP, "USER", UUID.randomUUID(), T0.plus(Duration.ofHours(1)));
        assertThat(link.usableAt(T0)).isTrue();
        link.use(T0);
        assertThat(link.usableAt(T0)).isFalse();
        assertThatThrownBy(() -> link.use(T0)).isInstanceOf(ConflictException.class);
        AccessLink expired = new AccessLink(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "a".repeat(64),
                PortalChannel.WHATSAPP, "USER", UUID.randomUUID(), T0);
        assertThat(expired.usableAt(T0)).isFalse();
    }

    @Test
    void sessionHasIdleAndAbsoluteTimeoutsAndFunnelStepsOnce() {
        PortalSession s = new PortalSession(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), AccessMethod.OTP, T0,
                Duration.ofMinutes(60), "10.0.0.1", "agent");
        Duration idle = Duration.ofMinutes(15);
        assertThat(s.usableAt(T0.plusSeconds(60), idle)).isTrue();
        assertThat(s.usableAt(T0.plus(Duration.ofMinutes(16)), idle)).isFalse();
        s.touch(T0.plus(Duration.ofMinutes(14)));
        assertThat(s.usableAt(T0.plus(Duration.ofMinutes(28)), idle)).isTrue();
        assertThat(s.usableAt(T0.plus(Duration.ofMinutes(61)), Duration.ofHours(2))).isFalse();
        assertThat(s.markSimulated(T0)).isTrue();
        assertThat(s.markSimulated(T0)).isFalse();
        assertThat(s.end(SessionStatus.ENDED, "logout", T0)).isTrue();
        assertThat(s.end(SessionStatus.REVOKED, "x", T0)).isFalse();
        assertThat(s.usableAt(T0, idle)).isFalse();
    }

    @Test
    void privacyNoticeIsVersionedByContent() {
        PortalConfiguration c = new PortalConfiguration(UUID.randomUUID(), settings(NOTICE));
        assertThat(c.getPrivacyNoticeVersion()).isEqualTo(1);
        assertThat(c.getPrivacyNoticeSha256()).hasSize(64);
        assertThat(c.apply(settings(NOTICE))).isFalse();
        assertThat(c.getPrivacyNoticeVersion()).isEqualTo(1);
        assertThat(c.apply(settings(NOTICE + " Nova versão."))).isTrue();
        assertThat(c.getPrivacyNoticeVersion()).isEqualTo(2);
        assertThatThrownBy(() -> new PortalConfiguration(UUID.randomUUID(), settings("curto")))
                .isInstanceOf(BusinessRuleException.class);
        assertThat(c.coversPortfolio(UUID.randomUUID())).isFalse();
    }

    @Test
    void serviceRequestNoteRules() {
        assertThatThrownBy(() -> new ServiceRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null,
                UUID.randomUUID(), "ABCDEFGHJK", ServiceRequestReason.OTHER, PortalChannel.WHATSAPP, " "))
                .hasMessageContaining("poucas palavras");
        ServiceRequest r = new ServiceRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null,
                UUID.randomUUID(), "ABCDEFGHJK", ServiceRequestReason.DISPUTE, PortalChannel.WHATSAPP, "nota");
        assertThat(ServiceRequestReason.DISPUTE.handoffReason()).isEqualTo("DISPUTE");
        r.anonymize();
        assertThat(r.getNote()).isNull();
    }
}
