package br.com.gestorcobranca.complaint.domain.repository;

import br.com.gestorcobranca.complaint.domain.model.Complaint;
import br.com.gestorcobranca.complaint.domain.model.ComplaintChannel;
import br.com.gestorcobranca.complaint.domain.model.ComplaintStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface ComplaintRepository {

    Complaint save(Complaint complaint);

    Optional<Complaint> findById(UUID id);

    Page<Complaint> search(Criteria criteria, Pageable pageable);

    List<Complaint> findByDebtorId(UUID debtorId);

    /** Aguardando resposta, prazo antes de {@code today} e ainda sem aviso de vencimento. */
    List<Complaint> findOverdueUnnotified(LocalDate today, int limit);

    /**
     * @param allowedCreditorIds credores do ator (nulo = todos)
     * @param dueUntil           "perto do prazo" nesta data: aguardando resposta com o aviso do canal já iniciado
     *                           (inclui vencidas)
     * @param overdue            só vencidas (true) / só no prazo (false)
     * @param assigneeId         responsável
     */
    record Criteria(Set<UUID> allowedCreditorIds, UUID creditorId, Set<ComplaintStatus> statuses,
                    Set<ComplaintChannel> channels, UUID debtorId, UUID caseId, UUID assigneeId, boolean unassigned,
                    LocalDate today, Boolean overdue, LocalDate dueUntil, String externalProtocol) {
    }
}
