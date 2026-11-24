package br.com.gestorcobranca.identity.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * API interna ({@code /api/**}): lista de permissão — só o usuário da assessoria. O credor do portal do credor (ADR
 * 0011), o devedor e qualquer tipo de ator que surgir ficam de fora mesmo autenticados.
 */
class InternalApiAccessTest {

    private static boolean allowed(Authentication auth) {
        return SecurityConfig.INTERNAL_ONLY.authorize(() -> auth, null).isGranted();
    }

    private static Authentication actor(String type) {
        TestingAuthenticationToken auth = new TestingAuthenticationToken("x", null, "ACTOR_" + type, "CASE_VIEW");
        auth.setAuthenticated(true);
        return auth;
    }

    @Test
    void soUsuarioDaAssessoriaEntraNaApiInterna() {
        assertThat(allowed(actor("USER"))).isTrue();
        assertThat(allowed(actor("CREDITOR"))).isFalse();
        assertThat(allowed(actor("DEBTOR"))).isFalse();
        assertThat(allowed(actor("INTEGRATION"))).isFalse();
        assertThat(allowed(actor("ALGUM_TIPO_NOVO"))).isFalse();
        assertThat(allowed(new AnonymousAuthenticationToken("k", "anon", AuthorityUtils.createAuthorityList("ACTOR_USER"))))
                .isFalse();
        assertThat(allowed(null)).isFalse();
    }
}
