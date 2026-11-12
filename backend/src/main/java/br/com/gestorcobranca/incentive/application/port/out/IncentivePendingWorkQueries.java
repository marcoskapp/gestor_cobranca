package br.com.gestorcobranca.incentive.application.port.out;

import br.com.gestorcobranca.shared.pendingwork.PendingWorkCount;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Contagens de "Minhas pendências" da remuneração variável (consultas indexadas; RLS do tenant
 * corrente). Sem escopo por credor: o acesso é por pessoa e as permissões de gestão alcançam todos os
 * negociadores. {@code singleId} é o do extrato (ou o da rodada, na classificação).
 */
public interface IncentivePendingWorkQueries {

    /**
     * Extratos prontos para aprovação que o usuário pode aprovar (segregação de
     * {@code CommissionStatement#approve}): não é o beneficiário, não fechou o período, não pediu nem
     * decidiu ajuste e não resolveu contestação do extrato; sem contestação aberta nem ajuste pendente.
     */
    PendingWorkCount statementsAwaitingApproval(UUID approverId);

    /** Contestações abertas por outra pessoa. */
    PendingWorkCount openDisputes(UUID userId);

    /** Ajustes aguardando segunda aprovação pedidos por outra pessoa, fora do próprio extrato. */
    PendingWorkCount adjustmentsAwaitingApproval(UUID userId);

    /**
     * Rodada de classificação de desempenho em revisão (no máximo uma), com o prazo de revisão; {@code singleId} é o da
     * rodada. Vencida = prazo já passou e a rotina ainda não a expirou.
     */
    PendingWorkCount tierRoundAwaitingReview(LocalDate today);
}
