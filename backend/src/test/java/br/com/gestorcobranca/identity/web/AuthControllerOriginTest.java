package br.com.gestorcobranca.identity.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/** A renovação de sessão deve aceitar a própria origem, pois o frontend é servido pelo backend. */
class AuthControllerOriginTest {

    @Test
    void ownOriginOmitsDefaultPorts() {
        MockHttpServletRequest https = new MockHttpServletRequest();
        https.setScheme("https");
        https.setServerName("app.gestorcobranca.com.br");
        https.setServerPort(443);
        assertThat(AuthController.ownOrigin(https)).isEqualTo("https://app.gestorcobranca.com.br");
    }

    @Test
    void ownOriginKeepsNonDefaultPorts() {
        MockHttpServletRequest local = new MockHttpServletRequest();
        local.setScheme("http");
        local.setServerName("localhost");
        local.setServerPort(8080);
        assertThat(AuthController.ownOrigin(local)).isEqualTo("http://localhost:8080");
    }
}
