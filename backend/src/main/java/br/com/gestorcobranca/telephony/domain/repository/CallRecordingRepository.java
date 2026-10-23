package br.com.gestorcobranca.telephony.domain.repository;

import br.com.gestorcobranca.telephony.domain.model.CallRecording;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CallRecordingRepository {

    CallRecording save(CallRecording recording);

    Optional<CallRecording> findById(UUID id);

    Optional<CallRecording> findByCallId(UUID callId);

    /** Gravações com arquivo guardado antes do corte (retenção). */
    List<CallRecording> findStoredBefore(Instant cutoff, int limit);

    List<CallRecording> findNotPurgedByDebtor(UUID debtorId);
}
