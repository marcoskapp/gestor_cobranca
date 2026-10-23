package br.com.gestorcobranca.telephony.application.service;

import br.com.gestorcobranca.shared.jobs.JobContext;
import br.com.gestorcobranca.shared.jobs.JobHandler;
import br.com.gestorcobranca.telephony.domain.model.Call;
import br.com.gestorcobranca.telephony.domain.model.InteractionState;
import br.com.gestorcobranca.telephony.domain.repository.CallRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Registro automático da chamada no caso ({@code telephony.record_interaction}), depois do commit
 * do encerramento: falha aqui (caso encerrado, collection indisponível) não desfaz a chamada e é
 * tentada de novo pelo job; reenvio não duplica (referência externa).
 */
@Component
class RecordInteractionJob implements JobHandler<RecordInteractionJob.Payload> {

    static final String TYPE = "telephony.record_interaction";

    private final CallRepository calls;
    private final CaseInteractions caseInteractions;

    RecordInteractionJob(CallRepository calls, CaseInteractions caseInteractions) {
        this.calls = calls;
        this.caseInteractions = caseInteractions;
    }

    record Payload(UUID callId) {
    }

    static String key(UUID callId) {
        return "interaction:" + callId;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Class<Payload> payloadType() {
        return Payload.class;
    }

    @Override
    public void handle(JobContext context, Payload payload) {
        Optional<Call> found = calls.findByIdForUpdate(payload.callId());
        if (found.isEmpty() || found.get().getInteractionState() != InteractionState.PENDING) {
            return;
        }
        Call call = found.get();
        if (call.getCaseId() == null || call.getTabulationCode() == null) {
            call.interactionSkipped();
        } else {
            caseInteractions.recordAutomatic(call).ifPresentOrElse(
                    result -> call.interactionRecorded(result.interaction().id()), call::interactionSkipped);
        }
        calls.save(call);
    }
}
