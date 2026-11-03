import { IndicatorHelp } from '../indicator.models';

/**
 * Indicadores da visão geral: "Suas pendências" na tela inicial e a lista "Minhas pendências".
 * (Os cartões da operação na tela inicial usam os indicadores dos painéis, `analytics.*`.)
 * Definições: docs/tecnico/desenho/pendencias.md; cálculo em `shared` (agregador de pendências) e
 * nas fontes de cada módulo.
 */
export const INDICATORS: readonly IndicatorHelp[] = [
  {
    id: 'app.pending.total',
    title: 'Suas pendências',
    what: 'Quantidade de itens que dependem de você agora: aprovações, retornos agendados, conversas aguardando resposta, prazos e outras tarefas, conforme o seu perfil e o seu escopo de credores.',
    how: 'Soma dos itens de cada área que você pode resolver. Prazos que só aparecem quando estão perto (solicitações de titulares, contestações, prazos jurídicos) entram a partir de 3 dias do vencimento. Atualiza a cada minuto enquanto a tela está aberta.',
    use: 'Comece pelos vencidos e pelos que vencem hoje; cada item leva à tela de ação já filtrada. Lista vazia significa que você está em dia.',
    screens: ['app.pending-work', 'app.home'],
  },
  {
    id: 'app.pending.urgency',
    title: 'Vencidos e que vencem hoje',
    what: 'Quantos itens das suas pendências já passaram do prazo e quantos vencem hoje.',
    how: 'Vencido: prazo encerrado (por exemplo, retorno não feito no horário, conversa além da meta de primeira resposta, prazo legal passado). Hoje: prazo até o fim do dia. Aprovações sem prazo não entram nessas contagens.',
    use: 'Vencidos primeiro: podem gerar quebra de compromisso com o devedor, multa ou prazo perdido. Se os vencidos se acumulam, peça redistribuição ao supervisor.',
    screens: ['app.pending-work'],
  },
];
