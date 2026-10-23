package br.com.gestorcobranca.incentive.domain.model.plan;

/** Distribuição do pool de equipe (§5.1). */
public enum PoolDistribution {
    /** Todo o pool vai para o supervisor da equipe. */
    SUPERVISOR,
    /** Pool dividido em partes iguais entre o supervisor e os membros ativos. */
    SUPERVISOR_AND_MEMBERS_EQUALLY
}
