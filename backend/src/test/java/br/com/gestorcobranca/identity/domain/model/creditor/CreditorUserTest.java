package br.com.gestorcobranca.identity.domain.model.creditor;

import br.com.gestorcobranca.identity.domain.model.LockoutPolicy;
import br.com.gestorcobranca.shared.domain.DomainException;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.Permission;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CreditorUserTest {

    private static final LockoutPolicy POLICY = new LockoutPolicy(5, Duration.ofMinutes(5), Duration.ofHours(1));
    private final Instant now = Instant.parse("2026-11-24T12:00:00Z");

    private CreditorUser invited() {
        return new CreditorUser(UUID.randomUUID(), "Ana Credor", "Ana@Credor.test", CreditorRole.FINANCEIRO,
                UUID.randomUUID(), now);
    }

    @Test
    void convidadaSoEntraDepoisDeAtivarComSenhaEAutenticadorJuntos() {
        CreditorUser user = invited();
        assertThat(user.getEmail()).isEqualTo("ana@credor.test");
        assertThat(user.canAuthenticate(now)).isFalse();
        assertThatThrownBy(() -> user.activate("hash", 1L, now)).isInstanceOf(DomainException.class);
        user.startActivation("SEGREDO");
        user.activate("hash", 10L, now);
        assertThat(user.getStatus()).isEqualTo(CreditorUserStatus.ACTIVE);
        assertThat(user.canAuthenticate(now)).isTrue();
        // o passo usado na ativação não serve de novo (login ou nova verificação)
        assertThat(user.acceptMfaStep(10L)).isFalse();
        assertThat(user.acceptMfaStep(11L)).isTrue();
    }

    @Test
    void senhaECodigoSomamNoMesmoContadorQueSoZeraAoAbrirASessao() {
        CreditorUser user = invited();
        user.startActivation("SEGREDO");
        user.activate("hash", 1L, now);
        for (int i = 0; i < 4; i++) {
            assertThat(user.registerFailure(now, POLICY)).isFalse();
        }
        // a verificação aceita não zera o histórico de bloqueios, mas a falha seguinte bloqueia
        assertThat(user.registerFailure(now, POLICY)).isTrue();
        assertThat(user.canAuthenticate(now)).isFalse();
        assertThat(user.canAuthenticate(now.plus(Duration.ofMinutes(6)))).isTrue();
    }

    @Test
    void refazerAcessoVoltaAoConviteSemSenhaNemAutenticadorERevogarApagaCredenciais() {
        CreditorUser user = invited();
        user.startActivation("SEGREDO");
        user.activate("hash", 1L, now);
        user.resetToInvitation(UUID.randomUUID(), now);
        assertThat(user.getStatus()).isEqualTo(CreditorUserStatus.INVITED);
        assertThat(user.getMfaSecret()).isNull();
        assertThat(user.getPasswordHash()).isNull();
        user.revoke(UUID.randomUUID(), "Saiu do credor", now);
        assertThat(user.getStatus()).isEqualTo(CreditorUserStatus.REVOKED);
        assertThatThrownBy(() -> user.changeRole(CreditorRole.LEITURA)).isInstanceOf(DomainException.class);
    }

    @Test
    void todoPerfilDoCredorTemSoPermissoesDoPortalEMontaOAtor() {
        for (CreditorRole role : CreditorRole.values()) {
            assertThat(Permission.CREDITOR_ONLY).as(role.name()).containsAll(role.permissions());
            assertThat(role.permissions()).as(role.name()).contains(Permission.CREDITOR_PORTAL_OVERVIEW);
            Actor actor = Actor.creditor(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    role.permissions());
            assertThat(actor.isCreditor()).isTrue();
        }
        assertThat(CreditorRole.FINANCEIRO.permissions()).contains(Permission.CREDITOR_STATEMENT_RESPOND);
        assertThat(CreditorRole.JURIDICO.permissions()).doesNotContain(Permission.CREDITOR_STATEMENT_RESPOND,
                Permission.CREDITOR_PAYMENT_REPORT);
    }

    @Test
    void trocarASenhaNaoZeraOContadorNemOBloqueio() {
        CreditorUser user = invited();
        user.startActivation("SEGREDO");
        user.activate("hash", 1L, now);
        for (int i = 0; i < 5; i++) {
            user.registerFailure(now, POLICY);
        }
        assertThat(user.isLocked(now)).isTrue();
        // redefinir a senha (quem controla o e-mail) não devolve tentativas do código
        user.definePassword("novo-hash", now);
        assertThat(user.isLocked(now)).isTrue();
        user.registerSuccessfulLogin(now.plus(Duration.ofHours(2)));
        assertThat(user.isLocked(now.plus(Duration.ofHours(2)))).isFalse();
    }
}
