package br.com.gestorcobranca.compliance.domain.repository;

import br.com.gestorcobranca.compliance.domain.model.DataSubjectRequest;
import br.com.gestorcobranca.compliance.domain.model.RequestStatus;
import br.com.gestorcobranca.compliance.domain.model.RequestType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DataSubjectRequestRepository {

    DataSubjectRequest save(DataSubjectRequest request);

    Optional<DataSubjectRequest> findById(UUID id);

    List<DataSubjectRequest> findByDebtorIdOrderByReceivedAtDesc(UUID debtorId);

    /** Abertas, ainda não marcadas como vencidas, com prazo até {@code now}. */
    List<DataSubjectRequest> findOpenDueBefore(Instant now, int limit);

    /**
     * @param onlyLinked somente solicitações já vinculadas a um devedor (atores com escopo restrito)
     */
    Page<DataSubjectRequest> search(RequestStatus status, RequestType type, UUID debtorId, String documentHash,
                                    Boolean overdue, boolean onlyLinked, Pageable pageable);
}
