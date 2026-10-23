/**
 * Métricas operacionais dos negociadores e remuneração variável (docs/tecnico/desenho/remuneracao-variavel.md):
 * fatos de desempenho materializados a partir de eventos, atribuição de crédito entre humano,
 * assistente, portal e campanha, planos de comissão versionados, razão de comissão com carência,
 * fechamento de período, extrato, contestação, ajuste, aprovação segregada, exportação para a
 * folha e monitoria de qualidade.
 * <p>
 * Dependências: {@code identity} (força de trabalho, tempo logado), {@code organization} e o
 * calendário de feriados do {@code compliance} (carência em dias úteis). Todos os demais fatos
 * chegam por eventos de integração de {@code shared.events}.
 */
@ApplicationModule(displayName = "Incentive",
        allowedDependencies = {"shared", "identity :: usecases", "organization :: usecases", "compliance :: usecases"})
package br.com.gestorcobranca.incentive;

import org.springframework.modulith.ApplicationModule;
