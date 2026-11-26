package br.com.gestorcobranca.sandbox;

import br.com.gestorcobranca.identity.domain.service.Totp;
import br.com.gestorcobranca.sandbox.domain.service.DemoTotp;
import org.junit.jupiter.api.RepeatedTest;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prende o TOTP da carga do sandbox ao do identity (decisão 1565): se o identity mudar algoritmo, dígitos ou passo, o
 * teste falha aqui, e não só no {@code up.sh}. Só código de teste depende do identity (o ArchUnit ignora testes).
 */
class DemoTotpMatchesIdentityTotpTest {

    @RepeatedTest(5)
    void oCodigoDaCargaEAceitoPeloIdentityNoMesmoPasso() {
        String secret = Totp.generateSecret();
        Instant now = Instant.now();
        long step = DemoTotp.step(now);

        assertThat(Totp.verify(secret, DemoTotp.code(secret, step), now)).hasValue(step);
        assertThat(Totp.verify(secret, DemoTotp.code(secret, step + 5), now)).isEmpty();
    }
}
