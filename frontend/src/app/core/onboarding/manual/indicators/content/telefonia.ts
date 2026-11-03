import { IndicatorHelp } from '../indicator.models';

/**
 * Indicadores da telefonia: comparação entre plataformas, painel da campanha do discador e presença
 * do operador. Definições: docs/tecnico/desenho/telefonia.md §5 (ritmo e abandono), §7 (presença) e
 * §13.5 (comparação); cálculo em `telephony/infrastructure/persistence/JdbcCallStatistics.java` e
 * `TelephonyAccountService`.
 */
export const INDICATORS: readonly IndicatorHelp[] = [
  // ------------------------------------------------------------- comparação entre plataformas
  {
    id: 'telephony.comparison.dialed',
    title: 'Discadas',
    what: 'Ligações de saída que cada plataforma de fato discou nos últimos 30 dias, com as que chegaram pela contingência.',
    how: 'Chamadas de saída com discagem iniciada na conta. Pela contingência são as que a conta recebeu porque a principal falhou.',
    use: 'Mostra o volume de cada fornecedor. Muitas pela contingência indicam instabilidade da conta principal.',
    screens: ['telephony.accounts'],
  },
  {
    id: 'telephony.comparison.asr',
    title: 'Atendimento (ASR)',
    what: 'Proporção das ligações discadas que foram atendidas (por pessoa ou por caixa postal).',
    how: 'Chamadas atendidas divididas pelas discadas da conta no período.',
    use: 'Compare plataformas na mesma carteira e horário: ASR menor numa delas costuma indicar problema de rota ou de identificação do número (bina) bloqueada.',
    screens: ['telephony.accounts', 'telephony.campaign'],
  },
  {
    id: 'telephony.comparison.failures',
    title: 'Falhas',
    what: 'Ligações que a plataforma recusou antes de discar ou que falharam durante a discagem.',
    how: 'Recusas mais falhas, divididas pelas discadas mais as recusas.',
    use: 'Taxa alta desperdiça tentativas e tempo dos operadores: confira credenciais, saldo e limites da conta no fornecedor, ou mude a rota para a outra plataforma.',
    screens: ['telephony.accounts'],
  },
  {
    id: 'telephony.comparison.times',
    title: 'Toque e conversa (média)',
    what: 'Tempo médio de toque até o atendimento e tempo médio de conversa com o operador.',
    how: 'Média do tempo de toque das chamadas discadas e encerradas, e média da conversa das chamadas conectadas a um operador.',
    use: 'Toque muito curto pode indicar chamada derrubada pela operadora; conversa muito curta, chamada caindo ou atendimento por máquina.',
    screens: ['telephony.accounts'],
  },
  {
    id: 'telephony.comparison.cost',
    title: 'Custo estimado',
    what: 'Estimativa do custo das ligações de cada plataforma no período.',
    how: 'Minutos tarifáveis (do atendimento ao fim de cada chamada) vezes a tarifa por minuto informada na conta. A fatura do fornecedor é a referência oficial; sem tarifa cadastrada, não há estimativa.',
    use: 'Junto com o ASR, mostra o custo por ligação atendida: priorize a plataforma que atende mais por real gasto, nas mesmas condições de carteira e horário.',
    screens: ['telephony.accounts'],
  },
  // ---------------------------------------------------------------- campanha do discador
  {
    id: 'telephony.dialer.abandon',
    title: 'Abandono',
    what: 'Ligações atendidas por uma pessoa que ficaram sem operador disponível para falar.',
    how: 'Atendidas por pessoa sem operador divididas pelas atendidas por pessoa (conectadas mais abandonadas), no dia de operação. O máximo padrão é 3%, regra de mercado; o devedor ouve uma mensagem informativa.',
    use: 'Perto do máximo, o discador reduz o ritmo sozinho. Abandono alto irrita o devedor e expõe a assessoria: coloque mais operadores disponíveis ou reduza a razão máxima.',
    screens: ['telephony.campaign'],
  },
  {
    id: 'telephony.dialer.connected',
    title: 'Conectadas',
    what: 'Ligações atendidas que chegaram a um operador, com as que caíram em caixa postal ao lado.',
    how: 'Chamadas conectadas a operador no dia; caixa postal é a detectada automaticamente pela plataforma.',
    use: 'Muitas caixas postais indicam horário ruim para a carteira: teste outras janelas de discagem.',
    screens: ['telephony.campaign'],
  },
  {
    id: 'telephony.dialer.ratio',
    title: 'Ritmo agora',
    what: 'Quantas ligações o discador faz, neste momento, para cada operador livre.',
    how: 'No modo preditivo, uma ligação dividida pela taxa de atendimento por pessoa do dia, limitada à razão máxima da campanha; começa em 1 até ter 30 discagens e cai quando o abandono se aproxima do máximo. O motivo aparece abaixo.',
    use: 'Ritmo baixo com operadores livres indica abandono alto ou aquecimento. Ritmo no máximo e operadores sempre ocupados: a campanha comporta mais operadores.',
    screens: ['telephony.campaign'],
  },
  {
    id: 'telephony.dialer.agents',
    title: 'Operadores livres',
    what: 'Operadores disponíveis na campanha agora, prontos para receber uma ligação.',
    how: 'Operadores com presença "disponível" na campanha, com as chamadas em andamento ao lado.',
    use: 'Sem operadores livres, o discador não disca. Acompanhe pausas longas na equipe.',
    screens: ['telephony.campaign'],
  },
  {
    id: 'telephony.presence.status',
    title: 'Presença na telefonia',
    what: 'Seu estado para o discador: disponível, em chamada, pós-atendimento, em pausa (com motivo) ou fora, e há quanto tempo.',
    how: 'Você escolhe entrar, ficar disponível, pausar ou sair; em chamada e pós-atendimento são definidos pelas ligações. Sem sinal do navegador por 90 segundos, o sistema tira você da telefonia (exceto em chamada).',
    use: 'Só quem está disponível recebe ligações do discador. Use o motivo certo na pausa: treino, monitoria, reunião e retaguarda contam como pausas produtivas.',
    screens: ['collection.queue', 'collection.case', 'telephony.agents'],
  },
];
