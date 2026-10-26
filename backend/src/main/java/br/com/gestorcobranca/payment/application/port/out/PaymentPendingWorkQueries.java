package br.com.gestorcobranca.payment.application.port.out;

import br.com.gestorcobranca.shared.pendingwork.PendingWorkCount;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Contagens de "Minhas pendências" do módulo (consultas indexadas; RLS do tenant corrente). */
public interface PaymentPendingWorkQueries {

    /**
     * Pagamentos externos informados aguardando revisão, exceto os informados pelo próprio revisor.
     *
     * @param creditorScope credores permitidos (nulo = todos)
     */
    PendingWorkCount externalReview(Set<UUID> creditorScope, UUID reviewerId);

    /**
     * Mesmo recorte de {@link #externalReview}, contado por acordo de destino (só os externos com acordo):
     * insumo do destaque pelo prazo da suspensão da quebra.
     *
     * @param creditorScope credores permitidos (nulo = todos)
     */
    Map<UUID, Long> externalReviewByAgreement(Set<UUID> creditorScope, UUID reviewerId);

    /**
     * Divergências de conciliação em aberto. Divergência sem credor só entra com {@code allCreditors}.
     *
     * @param creditorScope credores permitidos (nulo = todos)
     */
    PendingWorkCount openDivergences(Set<UUID> creditorScope);
}
