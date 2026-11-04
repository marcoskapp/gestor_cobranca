import { IndicatorHelp } from '../indicator.models';

/**
 * Indicadores do faturamento da plataforma às assessorias (área da plataforma e "Cobrança da plataforma" da
 * assessoria). Arquivo próprio do módulo "Administração", juntado ao catálogo pelo carregador de `indicator-index.ts`.
 * Definições: docs/negocio/api-publica.md §9; cálculo em `publicapi/application/service/platform/PlatformBillingEngine`
 * e `PlatformPriceCalculator`.
 */
export const INDICATORS: readonly IndicatorHelp[] = [
  {
    id: 'platform.billing.amount',
    title: 'Valor até agora',
    what: 'Quanto a assessoria deve à plataforma pelo uso da integração no ciclo em andamento, até este momento.',
    how: 'Mensalidade do ciclo (proporcional no primeiro, no último e no de transição) mais o uso acima das franquias, somando todos os tokens da assessoria, pela versão da política da plataforma que vale para o ciclo.',
    use: 'Acompanhe a receita da plataforma sem esperar o fechamento. Valor subindo rápido indica que a assessoria ampliou as integrações dos credores.',
    screens: ['platform.billing-tenant', 'publicapi.platform-charges', 'platform.billing'],
  },
  {
    id: 'platform.billing.projection',
    title: 'Projeção no fechamento',
    what: 'Estimativa do valor do ciclo da plataforma no dia do fechamento, se o ritmo de uso continuar.',
    how: 'O uso até agora mais a média diária do ciclo vezes os dias que faltam, arredondado para cima e calculado com a mesma política, inclusive as faixas de volume.',
    use: 'Antecipe a conversa com a assessoria quando a projeção passar muito do valor habitual ou de uma faixa de volume.',
    screens: ['platform.billing-tenant', 'publicapi.platform-charges', 'platform.billing'],
  },
  {
    id: 'platform.billing.billable-requests',
    title: 'Chamadas cobráveis',
    what: 'Chamadas bem-sucedidas de todos os tokens da assessoria no ciclo, que entram na cobrança da plataforma.',
    how: 'Soma das chamadas com sucesso de todos os tokens, de todos os credores, inclusive tokens revogados ou expirados no ciclo. Recusas por excesso, erros do credor e falhas da plataforma não contam.',
    use: 'Base da cobrança por chamada e do ranking de consumo. Compare com a franquia e com as faixas de volume da política.',
    screens: ['platform.billing-tenant', 'platform.billing', 'publicapi.platform-charges'],
  },
  {
    id: 'platform.billing.data',
    title: 'Dados recebidos e enviados',
    what: 'Volume de dados das chamadas bem-sucedidas no ciclo, em MB inteiros, recebidos e enviados.',
    how: 'Somado no ciclo por token e por direção e arredondado para cima (1 MB = 1.048.576 bytes); o total da assessoria é a soma dos tokens, como nos extratos dos credores.',
    use: 'Base da cobrança por MB. Volume alto com poucas chamadas indica arquivos grandes: vale revisar a franquia de dados da política.',
    screens: ['platform.billing-tenant', 'publicapi.platform-charges'],
  },
  {
    id: 'platform.billing.records',
    title: 'Registros processados',
    what: 'Títulos importados, pagamentos e contatos gravados pelas integrações da assessoria no ciclo.',
    how: 'Soma dos registros processados por todos os tokens da assessoria, de todos os credores, no ciclo em andamento.',
    use: 'Mostra o trabalho real feito pelas integrações. Cresce com as cargas de carteiras; compare com a franquia de registros.',
    screens: ['platform.billing-tenant', 'publicapi.platform-charges'],
  },
  {
    id: 'platform.billing.allowance',
    title: 'Uso das franquias',
    what: 'Quanto de cada franquia da política da plataforma a assessoria já usou no ciclo.',
    how: 'Uso do ciclo dividido pela franquia do ciclo (proporcional no ciclo mais curto). Acima de 100%, o excedente é cobrado pelo preço da política.',
    use: 'Franquia perto do fim antes da metade do ciclo indica excedente provável: avise a assessoria ou reveja a política no próximo ciclo.',
    screens: ['platform.billing-tenant', 'publicapi.platform-charges'],
  },
  {
    id: 'platform.billing.by-creditor',
    title: 'Consumo por credor',
    what: 'Quanto cada credor da assessoria contribuiu para o uso cobrado pela plataforma no ciclo.',
    how: 'Soma dos tokens de cada credor: chamadas, chamadas cobráveis, MB inteiros por token e registros. A soma dos credores fecha com o total da assessoria.',
    use: 'Mostra quais credores pesam na cobrança da plataforma e ajuda a assessoria a repassar ou negociar o custo com eles.',
    screens: ['platform.billing-tenant', 'publicapi.platform-charges'],
  },
  {
    id: 'platform.statement.total',
    title: 'Valor do extrato da plataforma',
    what: 'Valor cobrado pela plataforma da assessoria no ciclo, linha a linha, com o total.',
    how: 'Mensalidade mais o excedente de cada medida pela versão da política do ciclo; nas chamadas com faixas, cada faixa é calculada à parte e somada. Centavos arredondados ao par, por linha.',
    use: 'É o valor pronto para faturar. Uma revisão com motivo recalcula sobre o uso gravado e a marca do consumo prova o cálculo.',
    screens: ['platform.billing-tenant', 'publicapi.platform-charges', 'platform.billing'],
  },
  {
    id: 'platform.overview.revenue',
    title: 'Receita até agora',
    what: 'Soma do valor até agora de todas as assessorias com política, nos ciclos em andamento.',
    how: 'Para cada assessoria ativa, a mensalidade do ciclo mais o excedente já consumido, pela política da plataforma; assessorias sem política entram com zero.',
    use: 'Acompanhe a receita do mês da plataforma a qualquer momento e compare com os ciclos já fechados.',
    screens: ['platform.billing'],
  },
  {
    id: 'platform.overview.projection',
    title: 'Receita projetada',
    what: 'Estimativa da receita da plataforma no fechamento dos ciclos em andamento das assessorias.',
    how: 'Soma das projeções de cada assessoria: uso até agora mais a média diária vezes os dias restantes, precificado pela política de cada uma.',
    use: 'Planeje o caixa e identifique cedo assessorias que vão passar de faixa ou de franquia neste ciclo.',
    screens: ['platform.billing'],
  },
  {
    id: 'platform.overview.last-closed',
    title: 'Últimos extratos fechados',
    what: 'Soma do último extrato da plataforma fechado de cada assessoria, na revisão mais recente.',
    how: 'Para cada assessoria, o total do ciclo mais recente já fechado; assessorias sem extrato entram com zero.',
    use: 'Referência para comparar com a receita do ciclo em andamento e conferir o que já pode ser faturado.',
    screens: ['platform.billing'],
  },
  {
    id: 'platform.overview.without-policy',
    title: 'Assessorias sem política',
    what: 'Assessorias ativas que ainda não têm política de cobrança da plataforma cadastrada.',
    how: 'Contagem das assessorias ativas sem política; o uso delas aparece no ranking, mas sem valor.',
    use: 'Cadastre a política para começar a cobrar. O primeiro cadastro já vale no ciclo em andamento, sem cobrar ciclos passados.',
    screens: ['platform.billing'],
  },
  {
    id: 'platform.overview.rank',
    title: 'Posição no ranking de consumo',
    what: 'Ordem das assessorias pelo uso cobrável da integração no ciclo em andamento.',
    how: 'Ordenação pelas chamadas cobráveis do ciclo e, no empate, pelo valor até agora. A primeira posição é a de maior consumo.',
    use: 'Priorize o acompanhamento das maiores consumidoras e confira se a política delas está adequada ao volume.',
    screens: ['platform.billing'],
  },
];
