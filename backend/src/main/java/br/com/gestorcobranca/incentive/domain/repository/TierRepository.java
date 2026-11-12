package br.com.gestorcobranca.incentive.domain.repository;

import br.com.gestorcobranca.incentive.domain.model.tier.TierAssignment;
import br.com.gestorcobranca.incentive.domain.model.tier.TierRound;
import br.com.gestorcobranca.incentive.domain.model.tier.TierSettings;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Parâmetros, rodadas e pessoas da classificação de desempenho (RLS do tenant corrente). */
public interface TierRepository {

    /** Parâmetros gravados; vazio = valem os padrões. */
    Optional<TierSettings> settings();

    void saveSettings(TierSettings settings, UUID by, Instant at);

    void insertRound(TierRound round, Collection<TierAssignment> assignments);

    Optional<TierRound> findRound(UUID id);

    /**
     * Rodada com bloqueio compartilhado ({@code select ... for share}) até o fim da transação: o ajuste lê a situação
     * e grava sem que a publicação ou a expiração (que atualizam a rodada) passem no meio.
     */
    Optional<TierRound> findRoundForShare(UUID id);

    /** Rodada em revisão (no máximo uma, garantido por índice único). */
    Optional<TierRound> openRound();

    /** Publicação mais recente (a classificação vigente). */
    Optional<TierRound> lastPublished();

    /** Rodadas mais recentes primeiro. */
    List<TierRound> rounds(int limit);

    /** Já há rodada automática criada a partir deste instante (idempotência da rotina no período)? */
    boolean scheduledRoundSince(Instant since);

    /**
     * Grava a mudança de situação (publicação ou expiração) se a rodada ainda estiver em revisão; falso se outra
     * transação já a mudou.
     */
    boolean updateStatusFromProposed(TierRound round);

    List<TierAssignment> assignments(UUID roundId);

    /** Pessoas de várias rodadas numa consulta só (lista de rodadas), agrupadas pelo id da rodada. */
    Map<UUID, List<TierAssignment>> assignments(Collection<UUID> roundIds);

    Optional<TierAssignment> assignment(UUID roundId, UUID userId);

    /** Como {@link #assignment}, com bloqueio da linha: serializa ajustes simultâneos da mesma pessoa. */
    Optional<TierAssignment> assignmentForUpdate(UUID roundId, UUID userId);

    void updateAssignment(TierAssignment assignment);
}
