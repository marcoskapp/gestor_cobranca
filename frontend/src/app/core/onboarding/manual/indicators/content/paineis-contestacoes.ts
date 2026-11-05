import { IndicatorHelp } from '../indicator.models';

/**
 * Indicadores do painel de contestações (`/paineis/contestacoes`). Arquivo próprio do módulo "Painéis", juntado ao
 * catálogo pelo carregador de `indicator-index.ts`. Definições: docs/negocio/indicadores.md (Contestações); cálculo em
 * `analytics/application/service/AnalyticsQueryService#disputes` sobre as visões `src_dispute` e
 * `src_dispute_creditor_request`.
 */
export const INDICATORS: readonly IndicatorHelp[] = [
  {
    id: 'analytics.disputes.opened',
    title: 'Contestações abertas no período',
    what: 'Quantas contestações foram registradas no período, por qualquer origem: devedor, operador, credor ou jurídico.',
    how: 'Contagem das contestações com data de abertura no período e nos credores e carteiras filtrados.',
    use: 'Alta repentina num credor ou carteira costuma indicar problema na carga ou no produto: confira os motivos mais frequentes e converse com o credor.',
    screens: ['analytics.disputes'],
  },
  {
    id: 'analytics.disputes.open-now',
    title: 'Em aberto agora',
    what: 'Contestações ainda abertas ou em análise na data de referência, com as que já passaram do prazo de resposta ao devedor.',
    how: 'Contagem das contestações pendentes na data de referência; vencidas são as pendentes cujo prazo de resposta já passou.',
    use: 'Vencidas acima de zero são risco de reclamação e de cobrança indevida: redistribua a fila ou peça prioridade ao credor.',
    screens: ['analytics.disputes'],
  },
  {
    id: 'analytics.disputes.within-deadline',
    title: 'Resolvidas no prazo',
    what: 'Percentual das contestações resolvidas no período cuja resolução saiu até o prazo de resposta ao devedor.',
    how: 'Resolvidas até o prazo de resposta divididas pelas resolvidas no período; desistências não entram na conta.',
    use: 'Queda indica fila acumulada ou espera longa pelo credor: compare com o tempo de resposta do credor e com o backlog por responsável.',
    screens: ['analytics.disputes'],
  },
  {
    id: 'analytics.disputes.resolution-time',
    title: 'Tempo médio de resolução',
    what: 'Quantos dias, em média, as contestações resolvidas no período levaram da abertura até a resolução.',
    how: 'Soma dos dias entre abertura e resolução das contestações resolvidas no período, dividida pela quantidade delas.',
    use: 'Tempo subindo antecipa perda de prazo. Veja por motivo e por credor qual parte da operação está demorando mais.',
    screens: ['analytics.disputes'],
  },
  {
    id: 'analytics.disputes.upheld-rate',
    title: 'Procedência',
    what: 'Percentual das contestações resolvidas no período que deram razão ao devedor, total ou parcialmente.',
    how: 'Resolvidas como procedentes ou parcialmente procedentes divididas pelas resolvidas no período.',
    use: 'Procedência alta num credor aponta dívida enviada com erro: leve os casos ao credor para corrigir a origem e evitar cobrança indevida.',
    screens: ['analytics.disputes'],
  },
  {
    id: 'analytics.disputes.creditor-response',
    title: 'Retorno do credor',
    what: 'Pedidos formais enviados ao credor no período, quantos ele respondeu e em quanto tempo, em média.',
    how: 'Pedidos enviados no período; respondidos são os que receberam resposta, pela tela ou pela integração do credor; o tempo médio vai do envio à resposta, em horas.',
    use: 'Tempo alto ou muitos pedidos sem resposta mostram onde a contestação para: use os números na conversa de nível de serviço com o credor.',
    screens: ['analytics.disputes'],
  },
  {
    id: 'analytics.disputes.creditor-awaiting',
    title: 'Aguardando o credor',
    what: 'Pedidos ao credor ainda sem resposta na data de referência, com os que já passaram do prazo pedido ao credor.',
    how: 'Contagem dos pedidos enviados e não respondidos; vencidos são os que passaram do prazo pedido. O prazo ao devedor continua correndo.',
    use: 'Pedidos vencidos recebem cobrança automática por e-mail; se continuarem parados, escale ao contato do credor antes do prazo do devedor.',
    screens: ['analytics.disputes'],
  },
  {
    id: 'analytics.disputes.backlog',
    title: 'Fila por responsável',
    what: 'Contestações em aberto de cada analista e as sem responsável, com as vencidas no prazo interno e no prazo de resposta e as que aguardam o credor.',
    how: 'Contagem das contestações pendentes na data de referência por responsável atual. Quem não gerencia contestações vê só as pessoas do próprio escopo.',
    use: 'Carga desigual ou vencidas concentradas em uma pessoa pedem redistribuição; muitas sem responsável pedem distribuição automática ou atribuição.',
    screens: ['analytics.disputes'],
  },
];
