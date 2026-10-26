package br.com.gestorcobranca.helpdesk.infrastructure.provider.github;

import br.com.gestorcobranca.helpdesk.application.port.out.TicketTracker.CreatedIssue;
import br.com.gestorcobranca.helpdesk.application.port.out.TicketTracker.NewIssue;
import br.com.gestorcobranca.helpdesk.application.port.out.TicketTracker.TrackerException;
import br.com.gestorcobranca.shared.json.JsonCodec;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Cliente do GitHub com o servidor substituído por um dublê (nenhuma chamada real). */
class GitHubTicketTrackerTest {

    private final JsonCodec json = new JsonCodec(JsonMapper.builder().build());
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

    private GitHubTicketTracker tracker(String token, boolean requirePrivate) {
        return new GitHubTicketTracker(new GitHubProperties("marcoskapp/gestor_cobranca", token,
                URI.create("https://github-api.test"), List.of("suporte"), requirePrivate, null, null), json, meters, builder);
    }

    @Test
    void createsTheIssueWithLabelsAndMinimalHeaders() {
        GitHubTicketTracker t = tracker("github_pat_teste", false);
        server.expect(requestTo("https://github-api.test/repos/marcoskapp/gestor_cobranca/issues"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer github_pat_teste"))
                .andExpect(header(HttpHeaders.ACCEPT, "application/vnd.github+json"))
                .andExpect(header("X-GitHub-Api-Version", "2022-11-28"))
                .andExpect(jsonPath("$.title").value("[Suporte] Erro"))
                .andExpect(jsonPath("$.labels[0]").value("suporte"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("marcador-x")))
                .andRespond(withSuccess("{\"number\":12,\"html_url\":\"https://github.com/marcoskapp/gestor_cobranca/issues/12\"}",
                        MediaType.APPLICATION_JSON));
        CreatedIssue issue = t.create(new NewIssue("[Suporte] Erro", "corpo <!-- marcador-x -->", List.of("suporte")));
        assertThat(issue.number()).isEqualTo(12);
        assertThat(issue.url()).endsWith("/issues/12");
        assertThat(meters.counter("integration.calls", "provider", "github", "operation", "create_issue", "outcome", "ok")
                .count()).isEqualTo(1);
        server.verify();
    }

    @Test
    void readsRepositoryVisibilityAndFindsAnIssueByMarker() {
        GitHubTicketTracker t = tracker("tk", true);
        assertThat(t.requiresPrivateRepository()).isTrue();
        server.expect(requestTo("https://github-api.test/repos/marcoskapp/gestor_cobranca"))
                .andRespond(withSuccess("{\"private\":false}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://github-api.test/repos/marcoskapp/gestor_cobranca/issues?")))
                .andExpect(queryParam("labels", "suporte")).andExpect(queryParam("state", "all"))
                .andRespond(withSuccess("[{\"number\":3,\"html_url\":\"https://github.com/x/3\",\"body\":\"outro\"},"
                        + "{\"number\":4,\"html_url\":\"https://github.com/x/4\",\"body\":\"a <!-- mk-1 --> b\"}]",
                        MediaType.APPLICATION_JSON));
        assertThat(t.repositoryIsPrivate()).isFalse();
        assertThat(t.findByMarker("mk-1", Instant.parse("2026-10-26T10:00:00.123Z")))
                .contains(new CreatedIssue(4, "https://github.com/x/4"));
        server.verify();
    }

    @Test
    void mapsErrorsToStableCodes() {
        GitHubTicketTracker t = tracker("tk", false);
        server.expect(requestTo("https://github-api.test/repos/marcoskapp/gestor_cobranca/issues"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        assertThatThrownBy(() -> t.create(new NewIssue("t", "b", List.of())))
                .isInstanceOfSatisfying(TrackerException.class, e -> {
                    assertThat(e.code()).isEqualTo("token_rejected");
                    assertThat(e.transientFailure()).isFalse();
                });
        server.reset();
        server.expect(requestTo("https://github-api.test/repos/marcoskapp/gestor_cobranca/issues"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).header("retry-after", "60"));
        assertThatThrownBy(() -> t.create(new NewIssue("t", "b", List.of())))
                .isInstanceOfSatisfying(TrackerException.class, e -> assertThat(e.transientFailure()).isTrue());
        server.reset();
        server.expect(requestTo("https://github-api.test/repos/marcoskapp/gestor_cobranca/issues"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        assertThatThrownBy(() -> t.create(new NewIssue("t", "b", List.of())))
                .isInstanceOfSatisfying(TrackerException.class, e -> assertThat(e.code()).isEqualTo("unavailable"));
        server.reset();
        server.expect(requestTo("https://github-api.test/repos/marcoskapp/gestor_cobranca/issues"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> t.create(new NewIssue("t", "b", List.of())))
                .isInstanceOfSatisfying(TrackerException.class, e -> assertThat(e.code()).isEqualTo("repository_not_found"));
    }

    @Test
    void withoutTokenTheTrackerIsUnavailableAndCallsNothing() {
        GitHubTicketTracker t = tracker(null, false);
        assertThat(t.configured()).isFalse();
        assertThatThrownBy(() -> t.create(new NewIssue("t", "b", List.of())))
                .isInstanceOfSatisfying(TrackerException.class, e -> assertThat(e.code()).isEqualTo("not_configured"));
        server.verify();
    }

    @Test
    void invalidConfigurationPreventsStartup() {
        assertThatThrownBy(() -> new GitHubTicketTracker(new GitHubProperties("sem-barra", "tk",
                URI.create("http://api.github.com"), List.of("suporte"), false, null, null), json, meters, builder))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("repository")
                .hasMessageContaining("https");
    }
}
