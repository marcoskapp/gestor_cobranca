package br.com.gestorcobranca.identity.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class UserTest {

    private final LockoutPolicy policy = new LockoutPolicy(3, Duration.ofMinutes(5), Duration.ofHours(1));
    private final Instant now = Instant.parse("2026-10-22T12:00:00Z");

    private User activeUser() {
        User user = new User("Ana", "Ana@Alfa.Local ", null);
        user.definePassword("hash", now);
        return user;
    }

    @Test
    void normalizesEmailAndActivatesOnFirstPassword() {
        User user = new User("Ana", " Ana@Alfa.Local", null);
        assertThat(user.getEmail()).isEqualTo("ana@alfa.local");
        assertThat(user.getStatus()).isEqualTo(UserStatus.PENDING_ACTIVATION);
        user.definePassword("hash", now);
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.canAuthenticate(now)).isTrue();
    }

    @Test
    void locksProgressivelyAfterConsecutiveFailures() {
        User user = activeUser();
        assertThat(user.registerFailedLogin(now, policy)).isFalse();
        assertThat(user.registerFailedLogin(now, policy)).isFalse();
        assertThat(user.registerFailedLogin(now, policy)).isTrue();
        assertThat(user.getLockedUntil()).isEqualTo(now.plus(Duration.ofMinutes(5)));
        assertThat(user.canAuthenticate(now.plusSeconds(60))).isFalse();

        Instant later = now.plus(Duration.ofMinutes(6));
        assertThat(user.canAuthenticate(later)).isTrue();
        user.registerFailedLogin(later, policy);
        user.registerFailedLogin(later, policy);
        user.registerFailedLogin(later, policy);
        assertThat(user.getLockedUntil()).as("segundo bloqueio dobra a duração").isEqualTo(later.plus(Duration.ofMinutes(10)));
    }

    @Test
    void successfulLoginResetsLockoutHistory() {
        User user = activeUser();
        user.registerFailedLogin(now, policy);
        user.registerSuccessfulLogin(now);
        assertThat(user.getFailedAttempts()).isZero();
        assertThat(user.getLastLoginAt()).isEqualTo(now);
    }

    @Test
    void rejectsReusedTotpStep() {
        User user = activeUser();
        user.startMfaEnrollment("SECRET");
        user.confirmMfaEnrollment(now, 100);
        assertThat(user.acceptMfaStep(100)).isFalse();
        assertThat(user.acceptMfaStep(99)).isFalse();
        assertThat(user.acceptMfaStep(101)).isTrue();
    }

    @Test
    void disabledUserCannotAuthenticate() {
        User user = activeUser();
        user.disable();
        assertThat(user.canAuthenticate(now)).isFalse();
        user.enable();
        assertThat(user.canAuthenticate(now)).isTrue();
    }
}
