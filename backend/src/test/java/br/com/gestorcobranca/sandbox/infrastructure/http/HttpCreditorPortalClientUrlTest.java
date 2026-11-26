package br.com.gestorcobranca.sandbox.infrastructure.http;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A carga só fala com a própria aplicação: senha, código e token nunca vão para outro endereço. */
class HttpCreditorPortalClientUrlTest {

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:8080", "http://127.0.0.1:8080/", "http://app:8080", "http://[::1]:8080"})
    void aceitaAPropriaAplicacao(String url) {
        assertThat(HttpCreditorPortalClient.requireLocal(URI.create(url))).isEqualTo(URI.create(url));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://exemplo.test", "https://app.exemplo.test", "ftp://localhost", "http://u:p@localhost:8080",
            "http://10.0.0.5:8080"})
    void recusaOutroEndereco(String url) {
        assertThatThrownBy(() -> HttpCreditorPortalClient.requireLocal(URI.create(url)))
                .isInstanceOf(IllegalStateException.class);
    }
}
