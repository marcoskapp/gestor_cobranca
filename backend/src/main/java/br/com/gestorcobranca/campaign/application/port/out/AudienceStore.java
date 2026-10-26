package br.com.gestorcobranca.campaign.application.port.out;

import br.com.gestorcobranca.campaign.application.port.in.SegmentUseCases.AudienceCount;
import br.com.gestorcobranca.campaign.domain.model.AudienceSnapshot;
import br.com.gestorcobranca.campaign.domain.model.SegmentCriteria;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read model de audiência ({@code campaign.audience_case}): projeção própria dos casos de cobrança,
 * avaliada em SQL com a mesma semântica de {@code SegmentMatcher}.
 */
public interface AudienceStore {

    /** Grava a posição do caso; nunca regride para uma versão anterior do caso. */
    void upsert(AudienceSnapshot snapshot);

    Optional<AudienceSnapshot> find(UUID caseId);

    /**
     * Grava o score de propensão publicado pelo scoring ({@code CaseScoreChanged}) se for mais recente
     * que o já aplicado. Depois do primeiro score por evento, a posição lida do collection não altera
     * mais o score (o evento é a fonte, ordenada pelo instante do cálculo).
     *
     * @return false se o caso ainda não está na projeção ou o cálculo é mais antigo
     */
    boolean applyScore(UUID caseId, int score, Instant calculatedAt);

    /**
     * Grava o vencimento da próxima parcela em aberto do acordo em vigor do caso (nulo = sem acordo em
     * vigor com parcela em aberto), lido do negotiation no momento — independe da versão do caso, porque
     * a baixa de parcela não muda necessariamente o caso no collection.
     */
    void applyNextInstallment(UUID caseId, LocalDate dueDate);

    /** Conta mais um acordo quebrado no caso (sem efeito se o caso ainda não está na projeção). */
    void incrementBrokenAgreements(UUID caseId);

    AudienceCount count(Selection selection);

    /**
     * Casos que atendem à seleção, ainda não estão inscritos na campanha (respeitando a reinscrição) e
     * não estão numa situação que pausaria a régua ({@code PauseRules}), em ordem de {@code case_id} a
     * partir do cursor.
     *
     * @param pausesOnAgreement acordo em vigor pausa a régua desta campanha
     */
    List<AudienceSnapshot> candidates(Selection selection, UUID campaignId, Integer reenrollAfterDays,
                                      boolean pausesOnAgreement, UUID afterCaseId, int limit);

    /**
     * @param creditorId  credor exigido (nulo = qualquer)
     * @param portfolioId carteira exigida (nula = qualquer)
     */
    record Selection(SegmentCriteria criteria, UUID creditorId, UUID portfolioId, LocalDate today, Instant now) {
    }
}
