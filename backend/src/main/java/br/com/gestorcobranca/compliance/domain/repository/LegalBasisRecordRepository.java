package br.com.gestorcobranca.compliance.domain.repository;

import br.com.gestorcobranca.compliance.domain.model.LegalBasisRecord;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LegalBasisRecordRepository {

    LegalBasisRecord save(LegalBasisRecord record);

    Optional<LegalBasisRecord> findById(UUID id);

    /** Registros do credor (todas as carteiras e o geral), do mais recente ao mais antigo. */
    List<LegalBasisRecord> findByCreditorIdOrderByValidFromDesc(UUID creditorId);

    List<LegalBasisRecord> findByCreditorIdIn(Collection<UUID> creditorIds);

    /**
     * Existe registro do mesmo alcance (credor e carteira, nula = geral) cuja vigência intercepta
     * {@code [from, until]} ({@code until} nulo = sem fim).
     */
    boolean existsOverlapping(UUID creditorId, UUID portfolioId, LocalDate from, LocalDate until);

    /** Registro vigente na data para o alcance exato (carteira nula = geral do credor). */
    Optional<LegalBasisRecord> findEffective(UUID creditorId, UUID portfolioId, LocalDate date);
}
