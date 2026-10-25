package br.com.gestorcobranca.identity.application.service;

import br.com.gestorcobranca.identity.domain.model.ActionToken;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

/** O token de ativação/redefinição nunca vai na query string (que chega aos logs do ALB). */
class CredentialLinkTest {

    @Test
    void tokenTravelsInTheFragmentNotInTheQueryString() {
        String activation = CredentialService.credentialLink("https://app.exemplo.com.br", ActionToken.Purpose.ACTIVATION, "abc123");
        String reset = CredentialService.credentialLink("https://app.exemplo.com.br", ActionToken.Purpose.PASSWORD_RESET, "xyz");

        assertThat(activation).isEqualTo("https://app.exemplo.com.br/ativar-conta#token=abc123");
        assertThat(reset).isEqualTo("https://app.exemplo.com.br/redefinir-senha#token=xyz");
        assertThat(URI.create(activation).getRawQuery()).isNull();
        assertThat(URI.create(reset).getRawQuery()).isNull();
    }
}
