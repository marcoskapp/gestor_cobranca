package br.com.gestorcobranca.compliance.application.port.in;

import java.util.Optional;
import java.util.UUID;

/**
 * Leitura mínima do aviso ao credor para o ingestion (SEG-20261124-D01h; parecer AQ-01/AQ-04 da 3a): só o id, para o
 * lote bloqueado guardar a ligação com o aviso. Porta própria, para o ingestion não enxergar a remessa nem o registro
 * ({@code CreditorErasureNoticeUseCases}, da equipe de atendimento ao titular; {@code ArchitectureTest}).
 */
public interface CreditorErasureNoticeStatusQueries {

    /**
     * Aviso ao credor do titular anonimizado, se o credor tinha títulos conservados dele. Exige {@code IMPORT_EXECUTE}
     * e acesso ao credor.
     */
    Optional<UUID> noticeFor(UUID debtorId, UUID creditorId);
}
