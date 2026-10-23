package br.com.gestorcobranca.telephony.application.service;

import br.com.gestorcobranca.telephony.domain.model.Call;
import br.com.gestorcobranca.telephony.domain.model.CallRecording;
import br.com.gestorcobranca.telephony.domain.repository.CallRecordingRepository;
import br.com.gestorcobranca.telephony.domain.repository.CallRepository;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * LGPD: devedor anonimizado ({@code DebtorAnonymized}) — números das chamadas (valor, índice e
 * máscara) eliminados e arquivos de gravação descartados (voz é dado pessoal); os metadados
 * operacionais das chamadas permanecem.
 */
@Component
class TelephonyAnonymizer {

    private final CallRepository calls;
    private final CallRecordingRepository recordings;
    private final TelephonyMaintenance maintenance;
    private final TelephonyMetrics metrics;
    private final Clock clock;

    TelephonyAnonymizer(CallRepository calls, CallRecordingRepository recordings, TelephonyMaintenance maintenance,
                        TelephonyMetrics metrics, Clock clock) {
        this.calls = calls;
        this.recordings = recordings;
        this.maintenance = maintenance;
        this.metrics = metrics;
        this.clock = clock;
    }

    void anonymize(UUID debtorId) {
        metrics.eventConsumed("debtor_anonymized");
        Instant now = Instant.now(clock);
        for (Call call : calls.findAllByIdIn(calls.findIdsWithNumberByDebtor(debtorId))) {
            call.anonymizeNumber();
            calls.save(call);
        }
        for (CallRecording recording : recordings.findNotPurgedByDebtor(debtorId)) {
            maintenance.purge(recording, "ANONYMIZATION", now);
            metrics.recording("purged_anonymization");
        }
    }
}
