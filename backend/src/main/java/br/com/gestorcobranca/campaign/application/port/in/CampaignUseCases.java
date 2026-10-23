package br.com.gestorcobranca.campaign.application.port.in;

import br.com.gestorcobranca.campaign.domain.model.CadenceDefinition;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Campanhas e réguas versionadas por credor/carteira (F13). A régua ({@link CadenceDefinition}) tem
 * etapas com condição, ação, canal, janela de horário, espera e transição por resposta (RB07).
 * Publicar valida templates (publicados no communication para o credor ou a assessoria) e a
 * disponibilidade de ofertas; a versão publicada é imutável e usada pelas novas inscrições.
 * <p>
 * Escopo: o credor da campanha (404 leitura, 403 comando). Auditado.
 */
public interface CampaignUseCases {

    CampaignView create(CreateCampaignCommand command);

    CampaignView update(UUID campaignId, CampaignSettings settings);

    CampaignView get(UUID campaignId);

    PageResponse<CampaignView> search(CampaignFilter filter, PageQuery page);

    /** Cria (ou altera, se já existir) o rascunho da próxima versão da régua. */
    CadenceView saveDraft(UUID campaignId, CadenceDefinition definition);

    /** Publica o rascunho: a versão publicada anterior é retirada. */
    CadenceView publishDraft(UUID campaignId);

    List<CadenceView> cadences(UUID campaignId);

    CadenceView cadence(UUID cadenceId);

    /**
     * @param purpose              EXTRAJUDICIAL_COLLECTION (padrão), REMINDER, AGREEMENT_FOLLOW_UP, INFORMATIVE
     * @param exclusivityGroup     grupo de exclusividade (nulo = compatível com qualquer campanha)
     * @param reenrollAfterDays    nulo = o caso não volta a ser inscrito
     * @param attributionWindowDays padrão 7
     * @param paymentPauseDays     retomada após pagamento confirmado (nulo = só manual)
     * @param maxDeferralHours     adiamento máximo por horário/limite do porteiro (padrão 24)
     * @param exitWhenOutOfSegment encerrar a inscrição quando o caso sair do segmento (padrão sim)
     */
    record CampaignSettings(String name, String description, UUID segmentId, String purpose, Integer priority,
                            String exclusivityGroup, Integer reenrollAfterDays, Integer attributionWindowDays,
                            Integer paymentPauseDays, Integer maxDeferralHours, Boolean exitWhenOutOfSegment) {
    }

    record CreateCampaignCommand(String code, UUID creditorId, UUID portfolioId, CampaignSettings settings) {
    }

    /** @param status DRAFT, ACTIVE, PAUSED, FINISHED */
    record CampaignFilter(UUID creditorId, String status) {
    }

    record CampaignView(UUID id, String code, String name, String description, UUID creditorId, UUID portfolioId,
                        UUID segmentId, String purpose, String status, int priority, String exclusivityGroup,
                        Integer reenrollAfterDays, int attributionWindowDays, Integer paymentPauseDays,
                        int maxDeferralHours, boolean exitWhenOutOfSegment, UUID publishedCadenceId,
                        Integer publishedCadenceVersion, boolean hasDraft, Instant activatedAt, Instant pausedAt,
                        String pauseNote, Instant finishedAt, Instant createdAt, long version) {
    }

    record CadenceView(UUID id, UUID campaignId, int versionNumber, String status, CadenceDefinition definition,
                       Instant publishedAt, UUID publishedBy, Instant retiredAt, Instant createdAt, long version) {
    }
}
