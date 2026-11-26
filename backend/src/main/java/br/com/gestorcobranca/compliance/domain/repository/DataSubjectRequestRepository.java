package br.com.gestorcobranca.compliance.domain.repository;

import br.com.gestorcobranca.compliance.domain.model.DataSubjectRequest;
import br.com.gestorcobranca.compliance.domain.model.RequestStatus;
import br.com.gestorcobranca.compliance.domain.model.RequestType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DataSubjectRequestRepository {

    DataSubjectRequest save(DataSubjectRequest request);

    Optional<DataSubjectRequest> findById(UUID id);

    List<DataSubjectRequest> findByDebtorIdOrderByReceivedAtDesc(UUID debtorId);

    /**
     * Pedidos de eliminação executados cujo índice do titular está entre os informados (decisão 1471), sem os já
     * liberados (decisão 1604). Só id e índice.
     */
    List<ErasedSubjectMatch> findErasedSubjects(Collection<String> subjectHashes);

    /** Abertas, ainda não marcadas como vencidas, com prazo até {@code now}. */
    List<DataSubjectRequest> findOpenDueBefore(Instant now, int limit);

    /**
     * @param onlyLinked somente solicitações já vinculadas a um devedor (atores com escopo restrito)
     */
    Page<DataSubjectRequest> search(RequestStatus status, RequestType type, UUID debtorId, String documentHash,
                                    Boolean overdue, boolean onlyLinked, Pageable pageable);

    /** @param returnPending só com tentativa de retorno do titular ainda sem revisão (decisão 1604) */
    Page<DataSubjectRequest> search(RequestStatus status, RequestType type, UUID debtorId, String documentHash,
                                    Boolean overdue, boolean onlyLinked, Boolean returnPending, Pageable pageable);
}
