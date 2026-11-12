package br.com.gestorcobranca.scoring.application.port.out;

import br.com.gestorcobranca.scoring.domain.model.ContactChannel;
import br.com.gestorcobranca.scoring.domain.model.ContactFact;
import br.com.gestorcobranca.scoring.domain.model.ContactProfileRow;
import br.com.gestorcobranca.scoring.domain.service.ContactProfileCalculator.RateBase;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Fatos, perfis, base das taxas e desfechos da inteligência de contato ({@code scoring.contact_attempt},
 * {@code contact_profile}, {@code contact_rate_base}, {@code agreement_contact_outcome}). RLS restringe ao tenant da
 * transação.
 */
public interface ContactFactStore {

    /**
     * Grava o fato; idempotente pelo evento de origem.
     *
     * @param contactOrigin NEW (contato recebido em carga depois do cadastro do devedor), EXISTING ou nulo (sem contato)
     * @return false se já estava gravado
     */
    boolean record(ContactFact fact, String contactOrigin);

    /**
     * Marca como respondida a tentativa <strong>mais recente</strong> do devedor no canal e no credor (e no contato,
     * quando informado) entre {@code from} e {@code to}, só se ela ainda estiver sem sucesso. Tentativas mais antigas
     * nunca são marcadas: mensagens seguidas do devedor respondem a uma tentativa só.
     *
     * @param creditorId credor da resposta (nulo = tentativas sem credor)
     * @param contactId  contato da resposta (nulo = só tentativas sem contato identificado, a mesma série do perfil)
     * @return true se a tentativa foi marcada
     */
    boolean markLatestAttemptAnswered(UUID debtorId, UUID creditorId, UUID contactId, ContactChannel channel,
                                      Instant from, Instant to, UUID responseEventId);

    List<ContactFact> factsOfDebtor(UUID debtorId);

    /** Substitui o perfil do devedor pelos agregados informados. */
    void replaceProfiles(UUID debtorId, Collection<ContactProfileRow> rows, Instant updatedAt);

    List<ContactProfileRow> profilesOfDebtor(UUID debtorId);

    // ---------------------------------------------------------------- base das taxas (materializada)

    /**
     * Recalcula {@code scoring.contact_rate_base} do tenant com as tentativas desde {@code since}: tentativas e
     * sucessos por carteira e canal e por assessoria e canal, e por carteira (todos os canais) a mediana de tentativas
     * até o 1º sucesso das séries (devedor, contato, canal). @return linhas gravadas
     */
    int refreshRateBase(Instant since, Instant refreshedAt);

    /**
     * Tentativas e sucessos por canal da última manutenção (carteira informada, ou a assessoria inteira); vazio antes
     * da primeira manutenção.
     */
    Map<ContactChannel, RateBase> rates(UUID portfolioId);

    /** Mediana de tentativas até o 1º sucesso da carteira na última manutenção (nulo sem séries com sucesso). */
    Double medianAttemptsToFirstSuccess(UUID portfolioId);

    // ---------------------------------------------------------------- retenção e anonimização

    /** Retenção: até {@code limit} devedores com fatos anteriores a {@code before}, em ordem de id. */
    List<UUID> debtorsWithFactsBefore(Instant before, int limit);

    /** Retenção: apaga os fatos do devedor anteriores a {@code before}. @return fatos apagados */
    int deleteFactsOfDebtorBefore(UUID debtorId, Instant before);

    /**
     * Anonimização: apaga fatos e perfis do devedor e tira a identificação dos desfechos (instantes truncados para o
     * dia em {@code zone}, para a linha não ser religada ao acordo pelo instante exato).
     */
    Removal deleteByDebtor(UUID debtorId, ZoneId zone);

    record Removal(int facts, int profiles, int outcomes) {
    }

    // ---------------------------------------------------------------- desfecho por acordo

    Optional<OutcomeRow> findOutcome(UUID agreementId);

    /** Tentativas e contatos efetivos do devedor no credor antes do acordo (desde o acordo anterior). */
    Episode episode(UUID debtorId, UUID creditorId, UUID agreementId, Instant agreedAt);

    void insertOutcome(OutcomeRow row);

    /** Atualiza contagens, situação e o instante da situação de um desfecho identificado. */
    void updateOutcome(OutcomeRow row);

    /**
     * Retenção: tira a identificação de até {@code limit} desfechos encerrados de acordos anteriores a {@code before},
     * com os instantes truncados para o dia em {@code zone}.
     */
    int neutralizeOutcomesBefore(Instant before, int limit, ZoneId zone);

    /**
     * @param finalStatus ACTIVE, COMPLETED, BROKEN ou CANCELLED
     */
    record OutcomeRow(UUID id, UUID agreementId, UUID caseId, UUID debtorId, UUID creditorId, UUID portfolioId,
                      String personType, Instant agreedAt, Episode episode, String finalStatus, Instant statusAt,
                      Instant updatedAt) {
    }

    /**
     * @param firstEffectiveAt     primeiro contato efetivo do episódio (nulo sem contato efetivo)
     * @param lastEffectiveChannel canal do último contato efetivo
     */
    record Episode(int attempts, int effective, Instant firstEffectiveAt, ContactChannel lastEffectiveChannel) {
    }

    // ---------------------------------------------------------------- painel da carteira

    /**
     * @param creditorIds credores permitidos (vazio = todos)
     * @param personType  INDIVIDUAL, COMPANY ou nulo (todos)
     */
    record InsightFilter(Instant from, Instant to, Collection<UUID> creditorIds, UUID portfolioId, String personType) {
    }

    record HeatCell(int weekday, int startHour, long attempts, long successes) {
    }

    record ChannelCount(ContactChannel channel, long attempts, long successes, long debtorInitiated, long reads) {
    }

    /** @param position posição da 1ª tentativa com sucesso (nulo = nunca atendida no período) */
    record FirstSuccessCount(Integer position, long series) {
    }

    record PersonTypeCount(String personType, long attempts, long successes) {
    }

    /** @param origin NEW (contato recebido em carga depois do cadastro) ou EXISTING */
    record ContactOriginCount(String origin, long attempts, long successes) {
    }

    /** Acordos agrupados (contagem) por tentativas antes, dias até o acordo, situação e tipo de pessoa. */
    record OutcomeCount(int attemptsBefore, Integer daysFirstEffective, String finalStatus, String personType,
                        long agreements) {
    }

    List<HeatCell> heatmap(InsightFilter filter);

    List<ChannelCount> channels(InsightFilter filter);

    List<FirstSuccessCount> firstSuccess(InsightFilter filter);

    List<PersonTypeCount> attemptsByPersonType(InsightFilter filter);

    List<ContactOriginCount> attemptsByContactOrigin(InsightFilter filter);

    List<OutcomeCount> outcomes(InsightFilter filter);
}
