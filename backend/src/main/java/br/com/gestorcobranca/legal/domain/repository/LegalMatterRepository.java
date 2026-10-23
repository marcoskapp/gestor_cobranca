package br.com.gestorcobranca.legal.domain.repository;

import br.com.gestorcobranca.legal.domain.model.LegalMatter;
import br.com.gestorcobranca.legal.domain.model.LegalStage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LegalMatterRepository {

    LegalMatter save(LegalMatter matter);

    Optional<LegalMatter> findById(UUID id);

    /** Trava o caso jurídico para comandos (serializa alterações concorrentes do mesmo caso). */
    Optional<LegalMatter> findByIdForUpdate(UUID id);

    /** Caso jurídico não final (fase diferente de CLOSED e RETURNED_TO_CREDITOR) do caso de cobrança. */
    Optional<LegalMatter> findOpenByCollectionCaseId(UUID collectionCaseId);

    /** Instante do encerramento mais recente de um caso jurídico do caso de cobrança (janela de nova indicação). */
    Optional<Instant> lastClosedAtByCollectionCaseId(UUID collectionCaseId);

    /** Casos jurídicos não finais que contêm o título. */
    List<LegalMatter> findOpenByTitleId(UUID titleId);

    Page<LegalMatter> search(MatterSearchCriteria criteria, Pageable pageable);

    /** Contagem e somatórios por fase, no escopo informado (painel jurídico). */
    List<StageSummary> summarize(MatterSearchCriteria criteria);

    /**
     * @param matters quantidade de casos jurídicos na fase
     */
    record StageSummary(LegalStage stage, long matters, java.math.BigDecimal debtAmount,
                        java.math.BigDecimal expensesTotal, java.math.BigDecimal recoveredAmount) {
    }

    List<LegalMatter> findAllByIdIn(Collection<UUID> ids);
}
