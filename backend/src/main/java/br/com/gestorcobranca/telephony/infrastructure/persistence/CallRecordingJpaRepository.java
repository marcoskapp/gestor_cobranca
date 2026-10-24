package br.com.gestorcobranca.telephony.infrastructure.persistence;

import br.com.gestorcobranca.telephony.domain.model.CallRecording;
import br.com.gestorcobranca.telephony.domain.repository.CallRecordingRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface CallRecordingJpaRepository extends JpaRepository<CallRecording, UUID>, CallRecordingRepository {

    @Override
    Optional<CallRecording> findByCallId(UUID callId);

    @Override
    default List<CallRecording> findStoredBefore(Instant cutoff, int limit) {
        return storedBefore(cutoff, PageRequest.of(0, limit));
    }

    @Query("""
            select r from CallRecording r
             where r.status = br.com.gestorcobranca.telephony.domain.model.RecordingStatus.STORED and r.storedAt < :cutoff
             order by r.storedAt
            """)
    List<CallRecording> storedBefore(Instant cutoff, Pageable pageable);

    @Override
    @Query("""
            select r from CallRecording r where r.debtorId = :debtorId
               and r.status <> br.com.gestorcobranca.telephony.domain.model.RecordingStatus.PURGED
            """)
    List<CallRecording> findNotPurgedByDebtor(UUID debtorId);
}
