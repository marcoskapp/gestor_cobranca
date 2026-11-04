import { ScreenManual } from '../manual.models';

/**
 * Manual das telas do faturamento da plataforma às assessorias (módulo "Administração"). Arquivo próprio, juntado ao
 * manual do módulo pelo carregador de `manual-index.ts`. Regras: docs/negocio/api-publica.md §9.
 */
export const RULES: readonly string[] = [
  'A cobrança da plataforma à assessoria é independente das condições que a assessoria cobra dos credores: soma o uso de todos os tokens da assessoria, inclusive revogados ou expirados.',
  'Só a equipe da plataforma cadastra a política e fecha ou revisa os extratos da plataforma; a assessoria consulta, sem alterar.',
];

export const SCREENS: readonly ScreenManual[] = [
  {
    id: 'platform.billing',
    title: 'Faturamento da plataforma',
    objective: 'Quanto cada assessoria deve à plataforma pelo uso da integração no ciclo em andamento, com projeção e ranking.',
    purpose: [
      'Tela da equipe da plataforma. Cada assessoria tem a sua política de cobrança da plataforma, e a base é o uso somado de todos os tokens dela, de todos os credores.',
      'Mostra a receita até agora, a projeção pela média diária, o último extrato fechado de cada assessoria e as que ainda não têm política (o uso aparece, sem valor).',
    ],
    profiles: ['PLATAFORMA'],
    inputs: ['Uso medido de todos os tokens das assessorias e a política da plataforma de cada uma.'],
    outputs: ['Visão da receita da plataforma e acesso ao faturamento de cada assessoria.'],
    related: [
      { screen: 'platform.billing-tenant', why: 'Política, posição e extratos de uma assessoria.' },
      { screen: 'platform.tenants', why: 'Assessorias da plataforma.' },
    ],
    terms: ['assessoria', 'token-de-integracao', 'extrato-de-consumo'],
  },
  {
    id: 'platform.billing-tenant',
    title: 'Faturamento da assessoria',
    objective: 'Política da plataforma, ciclo em andamento e extratos de uma assessoria, com fechamento, revisão e exportação.',
    purpose: [
      'A política tem vigência e versões: dia de fechamento, mensalidade, franquias e preço do excedente por chamada (único ou por faixas de volume), por MB recebido, por MB enviado e por registro. A primeira versão vale já no ciclo em andamento; as seguintes, a partir do próximo ciclo.',
      'O ciclo fecha no fim do dia de fechamento e o próximo começa às 00:00 do dia seguinte. Primeiro, último e de transição têm mensalidade, franquias e limites das faixas proporcionais aos dias. Só chamadas bem-sucedidas contam; dados em MB inteiros por token.',
      'Ciclos encerrados fecham sozinhos de madrugada. Uma nova revisão exige motivo e a anterior fica guardada; ciclos encerrados antes do primeiro cadastro não são cobrados.',
    ],
    profiles: ['PLATAFORMA'],
    inputs: ['Uso medido de todos os tokens da assessoria e a política da plataforma.'],
    outputs: ['Extratos da plataforma imutáveis, com detalhamento por credor, token e operação, exportáveis em planilha.'],
    related: [
      { screen: 'platform.billing', why: 'Todas as assessorias.' },
      { screen: 'platform.tenants', why: 'Situação da assessoria.' },
    ],
    terms: ['assessoria', 'token-de-integracao', 'extrato-de-consumo'],
  },
  {
    id: 'publicapi.platform-charges',
    title: 'Cobrança da plataforma',
    objective: 'O que a plataforma cobra da assessoria pelo uso da integração de todos os credores, para consulta.',
    purpose: [
      'Mostra a política que a plataforma aplica, o valor do ciclo em andamento com a projeção e os extratos fechados, com o mesmo cálculo da plataforma. Somente leitura.',
      'Não confunda com os extratos da integração de cada credor: aquela é a cobrança da assessoria ao credor, pelas condições do contrato.',
    ],
    profiles: ['ADMIN'],
    inputs: ['Uso medido de todos os tokens da assessoria e a política definida pela plataforma.'],
    outputs: ['Conferência dos valores cobrados pela plataforma e planilha do extrato.'],
    related: [
      { screen: 'publicapi.billing', why: 'Cobrança da assessoria a cada credor.' },
      { screen: 'publicapi.usage', why: 'Uso diário da integração.' },
    ],
    terms: ['token-de-integracao', 'extrato-de-consumo'],
  },
];
