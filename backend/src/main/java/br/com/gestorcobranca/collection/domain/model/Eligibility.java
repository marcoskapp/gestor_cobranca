package br.com.gestorcobranca.collection.domain.model;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;

/**
 * Resultado da elegibilidade para contato (RB02).
 *
 * @param reasons             motivos de inelegibilidade (vazio quando elegível)
 * @param contactableChannels canais sem restrição para o devedor/credor (independente de janela
 *                            de horário e limites, avaliados no momento do contato)
 */
public record Eligibility(boolean eligible, Set<EligibilityReason> reasons, Set<InteractionChannel> contactableChannels) {

    public Eligibility {
        reasons = reasons == null || reasons.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(reasons));
        contactableChannels = contactableChannels == null || contactableChannels.isEmpty() ? Set.of()
                : Set.copyOf(EnumSet.copyOf(contactableChannels));
        if (eligible && !reasons.isEmpty()) {
            throw new IllegalArgumentException("Caso elegível não tem motivos de inelegibilidade");
        }
    }

    public static Eligibility of(Collection<EligibilityReason> reasons, Collection<InteractionChannel> channels) {
        Set<EligibilityReason> r = reasons.isEmpty() ? EnumSet.noneOf(EligibilityReason.class) : EnumSet.copyOf(reasons);
        Set<InteractionChannel> c = channels.isEmpty() ? EnumSet.noneOf(InteractionChannel.class) : EnumSet.copyOf(channels);
        return new Eligibility(r.isEmpty(), r, c);
    }
}
