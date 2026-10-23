/**
 * Workflow jurídico (docs/design/juridico.md, Parte B; produto em documentação/workflow-juridico.md):
 * regras de elegibilidade e indicação, caso jurídico com fases e transições validadas, análise
 * de viabilidade, autorização do credor com evidência, medidas extrajudiciais, processos com
 * número CNJ validado, andamentos, prazos em dias úteis (feriados do compliance + suspensões
 * forenses), audiências, custas e despesas, escritórios parceiros com escopo restrito aos casos
 * atribuídos e documentos no {@code FileStorage}.
 * <p>
 * Dependências: {@code collection} (caso de cobrança e retenção LEGAL), {@code debt} (títulos,
 * contratos, garantias, prescrição e a porta de interrupção), {@code compliance} (calendário de
 * feriados), {@code identity} (usuários dos escritórios e responsáveis) e {@code organization}.
 * Acordos do {@code negotiation} são referenciados por id e acompanhados pelo evento
 * {@code AgreementStatusChanged} — o módulo não usa classes do negotiation.
 */
@ApplicationModule(displayName = "Legal",
        allowedDependencies = {"shared", "organization :: usecases", "identity :: usecases", "debt :: usecases",
                "collection :: usecases", "compliance :: usecases"})
package br.com.gestorcobranca.legal;

import org.springframework.modulith.ApplicationModule;
