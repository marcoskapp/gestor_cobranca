package br.com.gestorcobranca.campaign.application.port.in;

import br.com.gestorcobranca.campaign.domain.model.SegmentCriteria;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Segmentos salvos (F13) com filtros combináveis ({@link SegmentCriteria}) e contagem prévia. A
 * avaliação é feita sobre o read model de audiência do próprio módulo (projeção dos casos de
 * cobrança mantida a partir das interfaces públicas do collection e dos eventos de integração).
 * <p>
 * Escopo: segmento de credor exige o credor no escopo (404 leitura, 403 comando); segmento da
 * assessoria ({@code creditorId} nulo) exige acesso a todos os credores. Auditado.
 */
public interface SegmentUseCases {

    SegmentView create(CreateSegmentCommand command);

    SegmentView update(UUID segmentId, UpdateSegmentCommand command);

    SegmentView changeActive(UUID segmentId, boolean active);

    SegmentView get(UUID segmentId);

    PageResponse<SegmentView> search(SegmentFilter filter, PageQuery page);

    /** Contagem prévia de um segmento salvo (opcionalmente restrita a um credor/carteira). */
    AudienceCount count(UUID segmentId, UUID creditorId, UUID portfolioId);

    /** Contagem prévia de filtros ainda não salvos. */
    AudienceCount preview(PreviewCommand command);

    /**
     * Reconstrói o read model de audiência do credor (ou de todos, com escopo total) a partir do
     * collection — necessário só para casos anteriores à implantação do módulo; a projeção é mantida
     * pelos eventos. Assíncrono (job em lotes).
     */
    void rebuildAudience(UUID creditorId);

    record CreateSegmentCommand(UUID creditorId, String code, String name, String description,
                                SegmentCriteria criteria) {
    }

    record UpdateSegmentCommand(String name, String description, SegmentCriteria criteria) {
    }

    record PreviewCommand(UUID creditorId, UUID portfolioId, SegmentCriteria criteria) {
    }

    record SegmentFilter(UUID creditorId, Boolean active) {
    }

    record SegmentView(UUID id, UUID creditorId, String code, String name, String description,
                       SegmentCriteria criteria, boolean active, Instant createdAt, Instant updatedAt, long version) {
    }

    /**
     * @param cases       casos que atendem aos filtros
     * @param debtors     devedores distintos
     * @param openBalance saldo em aberto somado
     */
    record AudienceCount(long cases, long debtors, BigDecimal openBalance, Instant evaluatedAt) {
    }
}
