package br.com.gestorcobranca.collection.application.port.in;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * "Seu dia" no início (decisão D8 da revisão de produto de 2026-11): contagens do trabalho do usuário
 * corrente — casos com ele, retornos de hoje e atrasados, promessas vencendo e o próprio resultado do
 * dia — e, para quem distribui casos, o resumo das equipes que supervisiona (filas, retornos
 * atrasados e promessas da equipe). Somente contagens: nenhum dado de devedor. Respeita o escopo de
 * credores do usuário (RLS do tenant corrente).
 */
public interface MyDayQueries {

    /** Dia do usuário corrente; {@code operator} exige {@code CASE_WORK} e {@code team}, {@code CASE_ASSIGN}. */
    MyDayView myDay();

    /**
     * @param date     data de negócio (fuso de operação)
     * @param operator trabalho próprio (nulo sem {@code CASE_WORK})
     * @param team     equipes supervisionadas (nulo sem {@code CASE_ASSIGN} ou sem equipe supervisionada)
     */
    record MyDayView(LocalDate date, OperatorDay operator, TeamDay team) {
    }

    /**
     * @param assignedCases           casos ativos com o usuário
     * @param callbacksToday          retornos pendentes com horário ainda hoje (a partir de agora)
     * @param callbacksOverdue        retornos perdidos (até 30 dias, a janela da agenda) ou com horário já passado
     * @param promisesDueToday        promessas pendentes que vencem hoje nos casos do usuário
     * @param promisesAwaitingPayment promessas pendentes já vencidas, ainda na tolerância antes da quebra
     * @param interactionsToday       interações registradas pelo usuário hoje
     * @param effectiveContactsToday  dessas, com contato efetivo (pessoa certa)
     * @param promisesRecordedToday   promessas registradas pelo usuário hoje
     */
    record OperatorDay(long assignedCases, long callbacksToday, long callbacksOverdue, long promisesDueToday,
                       long promisesAwaitingPayment, long interactionsToday, long effectiveContactsToday,
                       long promisesRecordedToday) {
    }

    /**
     * @param teams            equipes ativas supervisionadas pelo usuário
     * @param members          operadores dessas equipes
     * @param assignedCases    casos ativos com os operadores das equipes
     * @param callbacksOverdue retornos atrasados dos operadores das equipes
     * @param promisesDueToday promessas que vencem hoje nos casos dos operadores das equipes
     * @param queues           filas ativas atendidas pelas equipes (visíveis no escopo do usuário)
     */
    record TeamDay(int teams, int members, long assignedCases, long callbacksOverdue, long promisesDueToday,
                   List<QueueDay> queues) {

        public TeamDay {
            queues = queues == null ? List.of() : List.copyOf(queues);
        }
    }

    /**
     * @param waiting casos elegíveis aguardando operador na fila
     * @param overdue desses, com a próxima ação vencida
     */
    record QueueDay(UUID queueId, String name, long waiting, long overdue) {
    }
}
