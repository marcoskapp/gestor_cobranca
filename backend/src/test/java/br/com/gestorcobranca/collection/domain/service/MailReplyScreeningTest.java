package br.com.gestorcobranca.collection.domain.service;

import br.com.gestorcobranca.collection.domain.model.DisputeMailReply.Verdicts;
import br.com.gestorcobranca.collection.domain.model.MailQuarantineReason;
import br.com.gestorcobranca.collection.domain.model.MailVerdict;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static br.com.gestorcobranca.collection.domain.model.MailVerdict.FAIL;
import static br.com.gestorcobranca.collection.domain.model.MailVerdict.GRAY;
import static br.com.gestorcobranca.collection.domain.model.MailVerdict.PASS;
import static org.assertj.core.api.Assertions.assertThat;

class MailReplyScreeningTest {

    private static final MailReplyScreening.Limits LIMITS = new MailReplyScreening.Limits(20, 1000, 2);
    private static final Verdicts OK = new Verdicts(PASS, PASS, PASS, PASS, PASS);

    private static Optional<MailQuarantineReason> screen(Verdicts v, boolean registered, long recent, long size,
                                                         int attachments) {
        return MailReplyScreening.screen(v, false, true, true, registered, recent, LIMITS, size, attachments);
    }

    @Test
    void authenticRegisteredSenderWithinLimitsIsAccepted() {
        assertThat(screen(OK, true, 0, 100, 2)).isEmpty();
    }

    @Test
    void authenticationRequiresDmarcOrSpfAndDkimWhenDomainHasNoPolicy() {
        assertThat(MailReplyScreening.authenticated(new Verdicts(FAIL, FAIL, PASS, PASS, PASS))).isTrue();
        assertThat(MailReplyScreening.authenticated(new Verdicts(PASS, PASS, GRAY, PASS, PASS))).isTrue();
        assertThat(MailReplyScreening.authenticated(new Verdicts(PASS, FAIL, GRAY, PASS, PASS))).isFalse();
        assertThat(MailReplyScreening.authenticated(new Verdicts(PASS, PASS, FAIL, PASS, PASS))).isFalse();
        assertThat(MailReplyScreening.authenticated(new Verdicts(PASS, PASS, MailVerdict.DISABLED, PASS, PASS))).isFalse();
    }

    @Test
    void reasonsFollowSeverityOrder() {
        Verdicts virusAndSpoof = new Verdicts(FAIL, FAIL, FAIL, FAIL, FAIL);
        assertThat(screen(virusAndSpoof, false, 99, 9999, 9)).contains(MailQuarantineReason.VIRUS);
        assertThat(screen(new Verdicts(FAIL, FAIL, FAIL, PASS, PASS), false, 0, 1, 0))
                .contains(MailQuarantineReason.AUTHENTICATION_FAILED);
        assertThat(screen(OK, false, 0, 1, 0)).contains(MailQuarantineReason.SENDER_NOT_REGISTERED);
        assertThat(screen(new Verdicts(PASS, PASS, PASS, PASS, FAIL), true, 0, 1, 0)).contains(MailQuarantineReason.SPAM);
        assertThat(screen(OK, true, 20, 1, 0)).contains(MailQuarantineReason.RATE_LIMITED);
        assertThat(screen(OK, true, 0, 1001, 0)).contains(MailQuarantineReason.TOO_LARGE);
        assertThat(screen(OK, true, 0, 1, 3)).contains(MailQuarantineReason.TOO_MANY_ATTACHMENTS);
        assertThat(MailReplyScreening.screen(OK, true, true, true, true, 0, LIMITS, 1, 0))
                .contains(MailQuarantineReason.MALFORMED);
        assertThat(MailReplyScreening.screen(OK, false, false, false, true, 0, LIMITS, 1, 0))
                .contains(MailQuarantineReason.DISPUTE_CLOSED);
        assertThat(MailReplyScreening.screen(OK, false, true, false, true, 0, LIMITS, 1, 0))
                .contains(MailQuarantineReason.ROUTE_EXPIRED);
    }

    @Test
    void securityReasonsAreNeverReleasable() {
        assertThat(MailQuarantineReason.VIRUS.releasable()).isFalse();
        assertThat(MailQuarantineReason.AUTHENTICATION_FAILED.releasable()).isFalse();
        assertThat(MailQuarantineReason.SENDER_NOT_REGISTERED.releasable()).isTrue();
        assertThat(MailVerdict.parse("pass")).isEqualTo(PASS);
        assertThat(MailVerdict.parse(null)).isEqualTo(MailVerdict.DISABLED);
        assertThat(MailVerdict.parse("weird")).isEqualTo(MailVerdict.DISABLED);
    }
}
