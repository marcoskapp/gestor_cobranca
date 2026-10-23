package br.com.gestorcobranca.debt.domain.model;

import java.util.EnumSet;
import java.util.Set;

/**
 * Situação da garantia. Transições:
 * <pre>
 * ACTIVE            → UNDER_ENFORCEMENT, RELEASED, WRITTEN_OFF
 * UNDER_ENFORCEMENT → ACTIVE (execução suspensa, ex.: acordo), SEIZED, CONSOLIDATED, RELEASED, WRITTEN_OFF
 * SEIZED            → AUCTIONED, RELEASED (bem devolvido após purgação da mora), WRITTEN_OFF
 * CONSOLIDATED      → AUCTIONED, WRITTEN_OFF
 * AUCTIONED, RELEASED, WRITTEN_OFF → finais
 * </pre>
 */
public enum CollateralStatus {
    /** Gravame vigente, sem medida de execução. */
    ACTIVE,
    /** Execução da garantia em andamento (notificação, busca e apreensão, procedimento em cartório). */
    UNDER_ENFORCEMENT,
    /** Bem apreendido. */
    SEIZED,
    /** Propriedade consolidada em nome do credor (alienação fiduciária de imóvel). */
    CONSOLIDATED,
    /** Bem alienado em leilão. */
    AUCTIONED,
    /** Gravame liberado (dívida quitada, acordo, substituição). */
    RELEASED,
    /** Garantia baixada sem recuperação (bem não localizado, perecido, sem valor). */
    WRITTEN_OFF;

    public Set<CollateralStatus> allowedTransitions() {
        return switch (this) {
            case ACTIVE -> EnumSet.of(UNDER_ENFORCEMENT, RELEASED, WRITTEN_OFF);
            case UNDER_ENFORCEMENT -> EnumSet.of(ACTIVE, SEIZED, CONSOLIDATED, RELEASED, WRITTEN_OFF);
            case SEIZED -> EnumSet.of(AUCTIONED, RELEASED, WRITTEN_OFF);
            case CONSOLIDATED -> EnumSet.of(AUCTIONED, WRITTEN_OFF);
            case AUCTIONED, RELEASED, WRITTEN_OFF -> EnumSet.noneOf(CollateralStatus.class);
        };
    }

    public boolean canTransitionTo(CollateralStatus target) {
        return allowedTransitions().contains(target);
    }

    public boolean isFinal() {
        return allowedTransitions().isEmpty();
    }
}
