package br.com.gestorcobranca.compliance.application.port.in;

import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;

import java.time.Instant;
import java.util.UUID;

/** Histórico de tentativas de contato do devedor nos credores do escopo do ator. */
public interface ContactAttemptQueries {

    PageResponse<AttemptView> listByDebtor(UUID debtorId, PageQuery page);

    record AttemptView(UUID id, UUID debtorId, UUID creditorId, String channel, Instant attemptedAt, String origin,
                       String referenceType, UUID referenceId, UUID recordedBy) {
    }
}
