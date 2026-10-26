package br.com.gestorcobranca.helpdesk.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Porta de saída para o rastreador de chamados (issues do GitHub, {@code GitHubTicketTracker}). O token fica em
 * segredo da AWS e chega à instância pela inicialização (nunca em arquivo versionado); sem token, a porta fica
 * {@link #configured() indisponível} e o chat continua funcionando sem a abertura de chamados.
 */
public interface TicketTracker {

    /** Com repositório e token configurados. */
    boolean configured();

    /** {@code dono/repositório}. */
    String repository();

    /** Exige repositório privado antes de abrir (trava {@code require-private-repository}). */
    boolean requiresPrivateRepository();

    /** Visibilidade atual do repositório, consultada no provedor. */
    boolean repositoryIsPrivate();

    /**
     * Procura uma issue já criada com o marcador (recuperação de uma abertura interrompida: idempotência).
     *
     * @param since a issue não pode ser anterior a este instante
     */
    Optional<CreatedIssue> findByMarker(String marker, Instant since);

    CreatedIssue create(NewIssue issue);

    record NewIssue(String title, String body, List<String> labels) {
    }

    record CreatedIssue(long number, String url) {
    }

    /** Falha do provedor; {@code code} estável para a resposta e as métricas. */
    class TrackerException extends RuntimeException {

        private final String code;
        private final boolean transientFailure;

        public TrackerException(String code, boolean transientFailure, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
            this.transientFailure = transientFailure;
        }

        public String code() {
            return code;
        }

        public boolean transientFailure() {
            return transientFailure;
        }
    }
}
