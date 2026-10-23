package br.com.gestorcobranca.telephony.application.service;

import br.com.gestorcobranca.collection.application.port.in.CaseUseCases;
import br.com.gestorcobranca.collection.application.port.in.CaseUseCases.CaseView;
import br.com.gestorcobranca.collection.application.port.in.InteractionUseCases;
import br.com.gestorcobranca.collection.application.port.in.InteractionUseCases.CallbackInput;
import br.com.gestorcobranca.collection.application.port.in.InteractionUseCases.PromiseInput;
import br.com.gestorcobranca.collection.application.port.in.InteractionUseCases.RecordInteractionCommand;
import br.com.gestorcobranca.collection.application.port.in.InteractionUseCases.RecordResult;
import br.com.gestorcobranca.collection.application.port.in.TabulationUseCases;
import br.com.gestorcobranca.telephony.domain.model.Call;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Registro da chamada como interação no caso pelo port público do collection (canal {@code PHONE},
 * origem {@code TELEPHONY}, referência externa {@code call:<id>} — reenvios não duplicam). Na
 * tabulação do operador, executa no contexto dele (a interação é do operador); a automática, como
 * sistema.
 */
@Component
class CaseInteractions {

    private static final Logger LOG = LoggerFactory.getLogger(CaseInteractions.class);
    /** Limite do collection para interações retroativas. */
    private static final Duration MAX_BACKDATE = Duration.ofDays(7);

    private final CaseUseCases cases;
    private final InteractionUseCases interactions;
    private final TabulationUseCases tabulations;
    private final TelephonyMetrics metrics;
    private final Clock clock;

    CaseInteractions(CaseUseCases cases, InteractionUseCases interactions, TabulationUseCases tabulations,
                     TelephonyMetrics metrics, Clock clock) {
        this.cases = cases;
        this.interactions = interactions;
        this.tabulations = tabulations;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** Tabulação do operador: erros do collection (tabulação inexistente, exigências) sobem ao operador. */
    RecordResult recordByOperator(Call call, String note, PromiseInput promise, CallbackInput callback) {
        RecordResult result = interactions.record(call.getCaseId(), command(call, note, promise, callback));
        metrics.interaction(result.duplicate() ? "duplicate" : "recorded");
        return result;
    }

    /**
     * Tabulação automática.
     *
     * @return interação registrada (ou já existente); vazio quando não há o que registrar
     */
    Optional<RecordResult> recordAutomatic(Call call) {
        CaseView kase = cases.get(call.getCaseId());
        if ("CLOSED".equals(kase.status())) {
            metrics.interaction("skipped_case_closed");
            return Optional.empty();
        }
        if (startedAt(call).isBefore(Instant.now(clock).minus(MAX_BACKDATE))) {
            metrics.interaction("skipped_too_old");
            return Optional.empty();
        }
        String code = call.getTabulationCode();
        boolean available = tabulations.list(true).stream().anyMatch(t -> t.code().equals(code));
        if (!available) {
            LOG.atWarn().addKeyValue("tabulation", code)
                    .log("Tabulação de chamada desativada ou inexistente; interação não registrada no caso");
            metrics.interaction("skipped_tabulation");
            return Optional.empty();
        }
        RecordResult result = interactions.record(call.getCaseId(), command(call, null, null, null));
        metrics.interaction(result.duplicate() ? "duplicate" : "recorded");
        return Optional.of(result);
    }

    private static RecordInteractionCommand command(Call call, String note, PromiseInput promise, CallbackInput callback) {
        return new RecordInteractionCommand("PHONE", call.getDirection().name(), null, call.getTabulationCode(), note,
                call.getContactId(), startedAt(call), call.getEndedAt(), promise, callback, null, "TELEPHONY",
                "call:" + call.getId());
    }

    private static Instant startedAt(Call call) {
        if (call.getDialStartedAt() != null) {
            return call.getDialStartedAt();
        }
        return call.getRingingAt() != null ? call.getRingingAt() : call.getQueuedAt();
    }
}
