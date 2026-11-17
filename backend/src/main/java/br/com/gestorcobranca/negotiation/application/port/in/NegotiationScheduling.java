package br.com.gestorcobranca.negotiation.application.port.in;

/**
 * Rotina diária do módulo (a mesma do agendamento das 00h20, {@code NegotiationJobs#daily}) para a assessoria da
 * execução corrente: propostas vencidas → {@code EXPIRED}; suspensões da quebra encerradas; parcelas vencidas →
 * {@code OVERDUE}; acordos que atingiram a regra de quebra → {@code BROKEN}. Cada etapa roda em lotes com transação
 * própria e é idempotente (rodar de novo no mesmo dia não muda nada).
 * <p>
 * Só para o ator de sistema da assessoria (rotinas e a carga do sandbox de demonstração, que percorre o histórico
 * fictício dia a dia); qualquer outro ator recebe {@code IllegalStateException}. Não tem rota HTTP.
 * <p>
 * Não usa a trava do agendamento ({@code negotiation.daily}): não chame ao mesmo tempo que o job das 00h20 em
 * produção (os lotes são idempotentes, mas a mesma quebra poderia ser tentada duas vezes e contar como falha).
 */
public interface NegotiationScheduling {

    void runDailyMaintenance();
}
