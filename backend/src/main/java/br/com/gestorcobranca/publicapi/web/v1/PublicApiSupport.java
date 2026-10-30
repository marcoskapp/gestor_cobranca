package br.com.gestorcobranca.publicapi.web.v1;

import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.PreconditionFailedException;
import br.com.gestorcobranca.shared.paging.ChangeCursor;
import br.com.gestorcobranca.shared.paging.ChangePage;
import br.com.gestorcobranca.shared.paging.ChangeQuery;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CurrentActor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Apoio comum aos endpoints v1: credor do token, consultas incrementais, ETag/If-Match e envelope das listas. O ETag é a
 * versão do registro ({@code "<versão>"}); {@code If-Match} aceita o ETag forte ou fraco e conflitos viram 412.
 */
@Component
class PublicApiSupport {

    private static final Pattern ETAG = Pattern.compile("^(?:W/)?\"(\\d{1,19})\"$");

    private final CurrentActor currentActor;
    private final Clock clock;
    private final Duration stabilization;

    PublicApiSupport(CurrentActor currentActor, Clock clock,
                     br.com.gestorcobranca.publicapi.application.PublicApiProperties properties) {
        this.currentActor = currentActor;
        this.clock = clock;
        this.stabilization = properties.syncStabilizationWindow();
    }

    /** Credor do token (o único do escopo do ator de integração). */
    UUID creditorId() {
        Actor actor = currentActor.get();
        if (actor.type() != Actor.ActorType.INTEGRATION || actor.creditorScope().all()
                || actor.creditorScope().creditorIds().size() != 1) {
            throw new IllegalStateException("Endpoint da API pública sem ator de integração");
        }
        return actor.creditorScope().creditorIds().iterator().next();
    }

    UUID tokenId() {
        return currentActor.get().id();
    }

    /** Consulta incremental com janela de estabilização (não entrega o que pode ainda estar em transação aberta). */
    ChangeQuery query(Instant updatedSince, String cursor, Integer limit) {
        ChangeCursor after = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                after = ChangeCursor.decode(cursor);
            } catch (IllegalArgumentException e) {
                throw new BusinessRuleException("public_api.cursor_invalid", "Cursor inválido");
            }
        }
        return new ChangeQuery(updatedSince, Instant.now(clock).minus(stabilization), after, limit == null ? 0 : limit);
    }

    <T, R> ListResponse<R> list(ChangePage<T> page, Function<T, R> mapper) {
        return new ListResponse<>(page.items().stream().map(e -> mapper.apply(e.value())).toList(),
                page.next() == null ? null : page.next().encode(), page.hasMore());
    }

    static <T> ResponseEntity<T> withEtag(T body, long version) {
        return ResponseEntity.ok().header(HttpHeaders.ETAG, etag(version)).body(body);
    }

    static String etag(long version) {
        return "\"" + version + "\"";
    }

    /** Versão do {@code If-Match}; nulo se ausente. Formato inválido → 412 (não aplica a alteração às cegas). */
    static Long ifMatch(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        Matcher m = ETAG.matcher(header.strip());
        if (!m.matches()) {
            throw new PreconditionFailedException("public_api.if_match_invalid",
                    "If-Match deve ser o ETag devolvido na leitura do recurso");
        }
        return Long.parseLong(m.group(1));
    }

    /**
     * Envelope de lista com paginação por cursor.
     *
     * @param data       itens da página
     * @param nextCursor envie em {@code cursor} na próxima chamada (guarde-o para a sincronização seguinte)
     * @param hasMore    há mais itens agora: chame de novo imediatamente
     */
    record ListResponse<T>(List<T> data, String nextCursor, boolean hasMore) {
    }
}
