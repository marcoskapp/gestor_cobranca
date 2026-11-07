import { IndicatorHelp } from '../indicator.models';

/**
 * Indicadores da visão geral: "Seu dia" e "Suas pendências" na tela inicial e a lista "Minhas
 * pendências". "Seu dia": docs/tecnico/desenho/collection.md (consulta `GET /collection/my-day`).
 * (Os cartões da operação na tela inicial usam os indicadores dos painéis, `analytics.*`.)
 * Definições: docs/tecnico/desenho/pendencias.md; cálculo em `shared` (agregador de pendências) e
 * nas fontes de cada módulo.
 */
export const INDICATORS: readonly IndicatorHelp[] = [
  {
    id: 'app.pending.total',
    title: 'Suas pendências',
    what: 'Quantidade de itens que dependem de você agora: aprovações, retornos agendados, conversas aguardando resposta, prazos e outras tarefas, conforme o seu perfil e o seu escopo de credores.',
    how: 'Soma dos itens de cada área que você pode resolver. Prazos que só aparecem quando estão perto (solicitações de titulares, contestações, prazos jurídicos) entram {{pendingwork.dueSoon}}. Atualiza a cada minuto enquanto a tela está aberta.',
    use: 'Comece pelos vencidos e pelos que vencem hoje; cada item leva à tela de ação já filtrada. Lista vazia significa que você está em dia.',
    screens: ['app.pending-work', 'app.home'],
  },
  {
    id: 'app.myday.cases',
    title: 'Casos com você',
    what: 'Casos de cobrança em andamento que estão atribuídos a você agora.',
    how: 'Conta os casos não encerrados em que você é o responsável, nos credores que você atende. É a mesma lista "Meus casos" da estação.',
    use: 'Use "Próximo caso" para receber o mais prioritário. Muitos casos parados com você atrasam a carteira: devolva à fila os que não vai trabalhar.',
    screens: ['app.home'],
  },
  {
    id: 'app.myday.callbacks',
    title: 'Retornos de hoje',
    what: 'Retornos e acompanhamentos que você combinou para o restante do dia, e os que já passaram do horário.',
    how: 'Hoje: retornos pendentes com horário a partir de agora até o fim do dia. Atrasados: horário já passado ou retorno perdido nos últimos 30 dias (a mesma janela da agenda).',
    use: 'Atrasados primeiro: o devedor esperava o contato no horário combinado. Abra a agenda para ligar ou reagendar.',
    screens: ['app.home'],
  },
  {
    id: 'app.myday.promises',
    title: 'Promessas vencendo hoje',
    what: 'Promessas de pagamento nos seus casos com data para hoje, e as já vencidas que ainda estão na tolerância.',
    how: 'Promessas pendentes nos casos atribuídos a você. Vencida na tolerância: a data passou, mas o pagamento ainda cumpre a promessa durante a tolerância configurada pela assessoria; depois disso ela quebra.',
    use: 'Lembre o devedor antes do fim do dia e ofereça o Pix. Promessa quebrada volta o caso para negociação.',
    screens: ['app.home'],
  },
  {
    id: 'app.myday.own-result',
    title: 'Seu resultado hoje',
    what: 'Seus contatos efetivos (com a pessoa certa) no dia, sobre o total de interações que você registrou, e as promessas obtidas.',
    how: 'Conta as interações registradas por você desde o início do dia (fuso de Brasília). Contato efetivo é o da tabulação marcada como contato com a pessoa certa.',
    use: 'Poucos contatos efetivos para muitas interações indicam telefones ruins ou horário errado: revise os contatos e a janela de ligação. O detalhe fica em "Meu desempenho".',
    screens: ['app.home'],
  },
  {
    id: 'app.myday.team',
    title: 'Sua equipe hoje',
    what: 'Resumo das equipes que você supervisiona: casos com os operadores, retornos atrasados, promessas do dia e as filas que elas atendem.',
    how: 'Somente as equipes em que você é o supervisor e os credores que você atende. Nas filas, "aguardando operador" são os casos elegíveis ainda sem responsável; "com próxima ação vencida" são os que já passaram do horário previsto.',
    use: 'Retornos atrasados e filas com ação vencida pedem redistribuição ou reforço. Ajuste em "Filas e distribuição".',
    screens: ['app.home'],
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
