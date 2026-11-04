import { IndicatorHelp } from '../indicator.models';

/**
 * Indicadores do jurídico: painel (dívida, custas, valor liberado judicialmente e prazos) e casos por fase.
 * "Recuperado" fica reservado a pagamentos confirmados (docs/negocio/indicadores.md §1; decisão 713).
 * Definições: docs/negocio/workflow-juridico.md e docs/tecnico/desenho/juridico.md (B.8 prazos);
 * cálculo em `legal/application/service/LegalMatterService.java` (painel) e `LawsuitService`
 * (valor recuperado por levantamento de valores).
 */
export const INDICATORS: readonly IndicatorHelp[] = [
  {
    id: 'legal.dashboard.open-debt',
    title: 'Dívida em aberto',
    what: 'Saldo dos títulos dos casos jurídicos ainda não encerrados.',
    how: 'Soma da dívida dos casos jurídicos em aberto, no escopo de credores e com os filtros escolhidos.',
    use: 'É o valor em disputa no jurídico. Compare com as custas para avaliar se vale seguir com cada fase.',
    screens: ['legal.dashboard'],
  },
  {
    id: 'legal.dashboard.expenses',
    title: 'Custas e despesas',
    what: 'Custas processuais e despesas lançadas nos casos jurídicos (cartório, diligências, honorários periciais e outras).',
    how: 'Soma das custas e despesas registradas nos casos, adiantadas pela assessoria, por escritório parceiro ou pelo credor.',
    use: 'Custas reembolsáveis entram na prestação de contas do credor. Custas altas frente à dívida indicam caso que pode não compensar: reavalie a viabilidade.',
    screens: ['legal.dashboard', 'legal.matter-detail'],
  },
  {
    id: 'legal.dashboard.recovered',
    title: 'Valor liberado judicialmente',
    what: 'Valores que o juízo liberou em favor do credor nos processos (alvarás e levantamentos de valores), registrados nos andamentos.',
    how: 'Soma dos levantamentos de valores lançados nos andamentos dos processos dos casos. Não é o valor recuperado: recuperado é só pagamento confirmado, e os acordos extrajudiciais aparecem na recuperação dos painéis.',
    use: 'Mostra o retorno da via judicial. Compare com as custas por fase para decidir em quais carteiras vale ajuizar.',
    screens: ['legal.dashboard'],
  },
  {
    id: 'legal.dashboard.deadlines-soon',
    title: 'Prazos próximos',
    what: 'Prazos processuais pendentes que vencem nos próximos 5 dias, contando hoje.',
    how: 'Prazos pendentes com vencimento até 5 dias corridos à frente. O vencimento já considera dias úteis, feriados, suspensões do tribunal e recesso, conforme a regra de contagem do prazo.',
    use: 'Distribua os prazos entre os responsáveis e registre o cumprimento assim que protocolar.',
    screens: ['legal.dashboard', 'legal.matter-detail'],
  },
  {
    id: 'legal.dashboard.deadlines-missed',
    title: 'Prazos perdidos',
    what: 'Prazos processuais que venceram sem registro de cumprimento, em casos ainda abertos.',
    how: 'Prazos marcados como perdidos pela verificação diária. Se o cumprimento ocorreu até o vencimento e só foi registrado depois, a correção tira o prazo desta contagem.',
    use: 'Deve ser sempre zero. Qualquer prazo perdido exige avaliar o impacto no processo com o escritório responsável imediatamente.',
    screens: ['legal.dashboard'],
  },
  {
    id: 'legal.dashboard.stages',
    title: 'Casos por fase',
    what: 'Casos jurídicos em cada fase do fluxo (da indicação e análise de viabilidade, passando pela autorização do credor, medidas extrajudiciais e processo, até o encerramento), com dívida, custas e valor liberado judicialmente de cada fase.',
    how: 'Contagens e somas dos casos pela fase atual, no escopo e filtros escolhidos.',
    use: 'Mostra onde os casos se acumulam. Muitos casos parados em autorização pedem cobrar a resposta do credor.',
    screens: ['legal.dashboard'],
  },
  {
    id: 'legal.matter.deadline',
    title: 'Prazo processual',
    what: 'Data de vencimento de cada prazo do caso, com quanto falta e a situação (pendente, cumprido, perdido ou cancelado).',
    how: 'Calculado a partir do fato inicial (intimação ou publicação) e da quantidade de dias: em dias úteis, sem fins de semana, feriados, suspensões do tribunal e, se ligado, o recesso de fim de ano; em dias corridos, o vencimento é prorrogado para o próximo dia com expediente. Alertas em 5, 2 e 0 dias úteis.',
    use: 'Registre o cumprimento assim que protocolar. Prazo perto do vencimento sem responsável ativo deve ser redistribuído.',
    screens: ['legal.matter-detail'],
  },
];
