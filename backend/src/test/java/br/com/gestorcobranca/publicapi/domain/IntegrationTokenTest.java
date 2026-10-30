package br.com.gestorcobranca.publicapi.domain;

import br.com.gestorcobranca.publicapi.domain.model.IntegrationToken;
import br.com.gestorcobranca.publicapi.domain.model.TokenScope;
import br.com.gestorcobranca.publicapi.domain.model.TokenSecret;
import br.com.gestorcobranca.publicapi.domain.model.TokenState;
import br.com.gestorcobranca.publicapi.domain.service.TokenHasher;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.security.Permission;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IntegrationTokenTest {

    private final SecureRandom random = new SecureRandom();
    private final TokenHasher hasher = new TokenHasher(Map.of("t1", "chave-de-teste-com-32-bytes-ok!!".getBytes()), "t1");
    private final Instant now = Instant.parse("2026-10-30T12:00:00Z");

    private IntegrationToken token(TokenSecret secret, Instant expiresAt) {
        return new IntegrationToken(UUID.randomUUID(), "ERP", null, secret.prefix(), hasher.hash(secret), "t1",
                Set.of(TokenScope.TITLES_READ), 60, List.of(), expiresAt, now);
    }

    @Test
    void rotationKeepsPreviousSecretOnlyDuringGrace() {
        TokenSecret first = TokenSecret.generate(random);
        IntegrationToken t = token(first, null);
        TokenSecret second = first.rotate(random);
        t.rotate(hasher.hash(second), "t1", Duration.ofMinutes(30), now);

        assertThat(t.matches(second, hasher, now)).isTrue();
        assertThat(t.matches(first, hasher, now.plusSeconds(60))).isTrue();
        assertThat(t.matches(first, hasher, now.plus(Duration.ofMinutes(31)))).isFalse();

        TokenSecret third = second.rotate(random);
        t.rotate(hasher.hash(third), "t1", Duration.ZERO, now.plusSeconds(120));
        assertThat(t.matches(second, hasher, now.plusSeconds(121))).isFalse();
        assertThat(t.matches(first, hasher, now.plusSeconds(121))).isFalse();
        assertThat(t.getRotationCount()).isEqualTo(2);
    }

    @Test
    void revocationIsImmediateFinalAndDropsGraceSecret() {
        TokenSecret first = TokenSecret.generate(random);
        IntegrationToken t = token(first, null);
        t.rotate(hasher.hash(first.rotate(random)), "t1", Duration.ofHours(1), now);
        t.revoke(UUID.randomUUID(), "vazamento", now.plusSeconds(1));
        assertThat(t.stateAt(now.plusSeconds(1))).isEqualTo(TokenState.REVOKED);
        assertThat(t.getPreviousValidUntil()).isNull();
        assertThatThrownBy(() -> t.revoke(UUID.randomUUID(), "de novo", now)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> t.rotate(hasher.hash(first), "t1", Duration.ZERO, now)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> t.updateSettings("x", null, 10, List.of())).isInstanceOf(ConflictException.class);
    }

    @Test
    void expirationIsDerivedFromTheInstant() {
        IntegrationToken t = token(TokenSecret.generate(random), now.plusSeconds(60));
        assertThat(t.stateAt(now)).isEqualTo(TokenState.ACTIVE);
        assertThat(t.stateAt(now.plusSeconds(60))).isEqualTo(TokenState.EXPIRED);
        assertThatThrownBy(() -> token(TokenSecret.generate(random), now.minusSeconds(1)))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void settingsAreValidated() {
        IntegrationToken t = token(TokenSecret.generate(random), null);
        assertThatThrownBy(() -> t.updateSettings("ok", null, 0, List.of())).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> t.updateSettings(" ", null, 10, List.of())).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> t.rotate("0".repeat(64), "t1", Duration.ofDays(2), now))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void scopesNeverGrantSensitiveDataOrManagementPermissions() {
        Set<Permission> all = TokenScope.permissionsOf(List.of(TokenScope.values()));
        assertThat(all).doesNotContain(Permission.DEBTOR_SENSITIVE_VIEW, Permission.PAYMENT_REVIEW,
                Permission.PAYMENT_REVERSE, Permission.IMPORT_APPROVE, Permission.USER_MANAGE, Permission.ROLE_MANAGE,
                Permission.TENANT_MANAGE, Permission.CREDITOR_MANAGE, Permission.PUBLIC_API_TOKEN_MANAGE,
                Permission.AGREEMENT_FORMALIZE, Permission.NEGOTIATION_APPROVE);
        assertThat(TokenScope.fromCode("titles:read")).isEqualTo(TokenScope.TITLES_READ);
        assertThatThrownBy(() -> TokenScope.fromCode("admin")).isInstanceOf(BusinessRuleException.class);
    }
}
