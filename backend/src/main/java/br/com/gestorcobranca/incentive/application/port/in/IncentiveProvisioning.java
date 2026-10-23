package br.com.gestorcobranca.incentive.application.port.in;

/** Configuração inicial do módulo numa assessoria. */
public interface IncentiveProvisioning {

    /**
     * Semeia o plano {@code PADRAO_MERCADO} (publicado a partir do mês corrente) se a assessoria
     * ainda não tiver nenhuma versão dele. Idempotente.
     *
     * @return true se o plano foi criado agora
     */
    boolean ensureDefaultPlan();
}
