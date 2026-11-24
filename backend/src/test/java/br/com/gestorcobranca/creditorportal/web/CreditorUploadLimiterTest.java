package br.com.gestorcobranca.creditorportal.web;

import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.support.MutableClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Limite de envios com documentos do portal do credor e teto do corpo antes de ler o multipart (SEG-F01). */
class CreditorUploadLimiterTest {

    @Test
    void decimoPrimeiroEnvioNoMesmoMinutoDa429ENaJanelaSeguintePassa() {
        MutableClock clock = new MutableClock();
        clock.set(Instant.parse("2026-11-24T12:00:05Z"));
        UUID person = UUID.randomUUID();
        Actor creditor = Actor.creditor(UUID.randomUUID(), person, UUID.randomUUID(), UUID.randomUUID(),
                java.util.Set.of(br.com.gestorcobranca.shared.security.Permission.CREDITOR_PORTAL_OVERVIEW));
        CurrentActor currentActor = mock(CurrentActor.class);
        when(currentActor.actorId()).thenReturn(person);
        when(currentActor.get()).thenReturn(creditor);
        AccessLogger accessLogger = mock(AccessLogger.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CreditorUploadLimiter limiter = new CreditorUploadLimiter(currentActor, accessLogger, registry, clock);

        for (int i = 0; i < CreditorUploadLimiter.LIMIT_PER_MINUTE; i++) {
            limiter.acquire("dispute_response", new MockHttpServletResponse());
        }
        MockHttpServletResponse rejected = new MockHttpServletResponse();
        assertThatThrownBy(() -> limiter.acquire("dispute_response", rejected))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("429");
        assertThat(rejected.getHeader("Retry-After")).isEqualTo("55");
        assertThat(registry.counter("creditor_portal.document_uploads.rejected", "operation", "dispute_response").count())
                .isEqualTo(1);
        verify(accessLogger).log(any());

        clock.set(Instant.parse("2026-11-24T12:01:00Z"));
        limiter.acquire("dispute_response", new MockHttpServletResponse());
    }

    @Test
    void multipartAcimaDoTetoESemTamanhoSaoRecusadosAntesDeLer() throws Exception {
        CreditorRequestSizeFilter filter = new CreditorRequestSizeFilter();

        MockHttpServletRequest big = multipart();
        big.addHeader("Content-Length", CreditorRequestSizeFilter.MAX_MULTIPART_BYTES + 1);
        big.setContent(new byte[0]);
        MockHttpServletResponse tooLarge = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(withLength(big, CreditorRequestSizeFilter.MAX_MULTIPART_BYTES + 1), tooLarge, chain);
        assertThat(tooLarge.getStatus()).isEqualTo(413);
        assertThat(tooLarge.getContentAsString()).contains("request.too_large");
        assertThat(chain.getRequest()).isNull();

        MockHttpServletResponse noLength = new MockHttpServletResponse();
        filter.doFilter(withLength(multipart(), -1), noLength, new MockFilterChain());
        assertThat(noLength.getStatus()).isEqualTo(411);

        MockFilterChain passed = new MockFilterChain();
        filter.doFilter(withLength(multipart(), 1024), new MockHttpServletResponse(), passed);
        assertThat(passed.getRequest()).isNotNull();

        // fora do portal do credor ou sem multipart, o filtro não age
        MockHttpServletRequest json = new MockHttpServletRequest("POST", "/api/creditor/v1/statements/x/contest");
        json.setContentType("application/json");
        MockFilterChain jsonChain = new MockFilterChain();
        filter.doFilter(withLength(json, -1), new MockHttpServletResponse(), jsonChain);
        assertThat(jsonChain.getRequest()).isNotNull();
    }

    private static MockHttpServletRequest multipart() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/creditor/v1/disputes/x/responses");
        request.setContentType("multipart/form-data; boundary=x");
        return request;
    }

    /** O {@code MockHttpServletRequest} calcula o tamanho pelo conteúdo; aqui ele é o informado no cabeçalho. */
    private static MockHttpServletRequest withLength(MockHttpServletRequest request, long length) {
        return new MockHttpServletRequest(request.getServletContext(), request.getMethod(), request.getRequestURI()) {
            {
                setContentType(request.getContentType());
            }

            @Override
            public long getContentLengthLong() {
                return length;
            }
        };
    }
}
