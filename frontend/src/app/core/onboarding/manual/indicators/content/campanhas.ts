import { IndicatorHelp } from '../indicator.models';

/**
 * Indicadores das campanhas: painel de execução (inscrições, etapas, mensagens e resultados
 * atribuídos) e testes A/B (métricas por braço, intervalo de confiança, diferença e amostra).
 * Definições: docs/tecnico/desenho/campanhas.md §4.6 (painel), §4.7 (atribuição) e §5 (testes A/B);
 * cálculo em `campaign/domain/service/ExperimentStatistics.java` e no painel da campanha.
 */
export const INDICATORS: readonly IndicatorHelp[] = [
  // ------------------------------------------------------------------------------- execução
  {
    id: 'campaign.execution.enrollments',
    title: 'Inscritos',
    what: 'Casos inscritos na campanha desde o início, com quantos ainda estão em andamento na régua.',
    how: 'Contagem das inscrições da campanha, de qualquer situação (em andamento, pausadas, encerradas). É o denominador das taxas do painel.',
    use: 'Inscrições abaixo do esperado indicam segmento restrito demais ou casos em outra campanha concorrente: revise o segmento e as supressões.',
    screens: ['campaign.execution'],
  },
  {
    id: 'campaign.execution.steps',
    title: 'Etapas executadas',
    what: 'Ações da régua realizadas (mensagens, tarefas, ligações), com as puladas e as que falharam.',
    how: 'Execuções das etapas por situação. Etapa pulada é a que não se aplicava (caso inelegível, condição não atendida, contato negado pelo porteiro de contato).',
    use: 'Muitas etapas puladas pedem rever condições e janelas da régua; falhas pedem conferir templates e canais.',
    screens: ['campaign.execution'],
  },
  {
    id: 'campaign.execution.failed',
    title: 'Inscrições com falha',
    what: 'Inscrições paradas por uma falha na execução de uma etapa, aguardando reprocessamento.',
    how: 'Contagem das inscrições em situação de falha no momento.',
    use: 'Reprocesse depois de corrigir a causa (template, canal, dado do devedor); enquanto isso, esses casos não recebem a régua.',
    screens: ['campaign.execution'],
  },
  {
    id: 'campaign.execution.replies',
    title: 'Respostas',
    what: 'Inscrições em que o devedor respondeu a alguma mensagem dentro do período de observação.',
    how: 'Inscrições com resposta atribuída (da inscrição até o fim da régua mais a janela de atribuição, padrão 7 dias), divididas pelas inscrições.',
    use: 'Mede o engajamento da régua. Taxa baixa pede testar outro template, canal ou horário (use um teste A/B).',
    screens: ['campaign.execution'],
  },
  {
    id: 'campaign.execution.agreements',
    title: 'Acordos atribuídos',
    what: 'Acordos formalizados por devedores inscritos dentro do período de observação, e o valor deles.',
    how: 'Leitura por intenção de tratar: o acordo conta para toda inscrição cujo período de observação o contém, uma vez por campanha, mesmo que tenha sido fechado por um negociador.',
    use: 'Mostra o resultado associado à campanha, não necessariamente causado por ela. Para medir o efeito real, compare com um grupo de controle num teste A/B.',
    screens: ['campaign.execution'],
  },
  {
    id: 'campaign.execution.recovered',
    title: 'Recuperado',
    what: 'Valor pago por devedores inscritos dentro do período de observação, líquido de estornos.',
    how: 'Pagamentos confirmados atribuídos às inscrições (mesma regra dos acordos), menos estornos; cada pagamento conta uma vez na campanha. Abaixo, quanto das inscrições pagou.',
    use: 'Compare com o custo das mensagens e com o grupo de controle antes de ampliar a campanha.',
    screens: ['campaign.execution'],
  },
  {
    id: 'campaign.execution.messages',
    title: 'Mensagens da régua',
    what: 'Mensagens enviadas pela campanha, quantas foram entregues e lidas, quantas não foram enviadas e quantas o porteiro de contato bloqueou.',
    how: 'Situação das mensagens das etapas da régua; entregues e lidas são percentuais das enviadas.',
    use: 'Entrega baixa indica número ou canal com problema; bloqueios altos indicam régua fora das regras de contato (horário, frequência, restrições).',
    screens: ['campaign.execution'],
  },
  // --------------------------------------------------------------------------------- testes A/B
  {
    id: 'campaign.experiment.interval',
    title: 'Estimativa e intervalo de confiança',
    what: 'O resultado de cada braço do teste (ponto) e a faixa onde o valor real provavelmente está, com 95% de confiança (barra).',
    how: 'O denominador é sempre o número de devedores do braço. Taxas usam o intervalo de Wilson; o valor por devedor, o intervalo da média. Quanto mais devedores, mais estreita a barra.',
    use: 'Barras largas significam pouca informação: espere a amostra mínima. Não decida olhando só o ponto.',
    screens: ['campaign.experiment'],
  },
  {
    id: 'campaign.experiment.difference',
    title: 'Diferença para a referência',
    what: 'Quanto o braço ficou acima ou abaixo da referência (o grupo de controle ou, sem controle, a primeira variante), com o intervalo de 95%.',
    how: 'Diferença absoluta entre as taxas (método de Newcombe) ou entre os valores médios por devedor (Welch). É significativa quando o intervalo da diferença não cruza o zero. O sistema não calcula a variação relativa (o "lift" em percentual).',
    use: 'Só adote a variante quando a diferença for significativa com a amostra planejada atingida. Olhar o resultado todo dia e parar no primeiro sinal aumenta a chance de falso positivo.',
    screens: ['campaign.experiment'],
  },
  {
    id: 'campaign.experiment.min-sample',
    title: 'Amostra mínima por braço',
    what: 'Quantidade de devedores que cada braço precisa ter antes de o resultado ser usado para decidir.',
    how: 'Definida no planejamento do teste (padrão 100). Abaixo dela, o braço mostra o aviso de amostra abaixo do mínimo.',
    use: 'Defina antes de começar, com base no tamanho do efeito que importa para a operação, e só decida quando todos os braços a atingirem.',
    screens: ['campaign.experiment'],
  },
  {
    id: 'campaign.experiment.mature',
    title: 'Somente horizonte completo',
    what: 'Filtro que considera só os devedores inscritos há pelo menos o horizonte de observação.',
    how: 'Ligado (padrão), todos os braços são observados pelo mesmo período. Desligado, entram devedores recentes, ainda em observação.',
    use: 'Para decidir, mantenha ligado. Desligado serve só para acompanhar o andamento.',
    screens: ['campaign.experiment'],
  },
  {
    id: 'campaign.experiment.effective-contact',
    title: 'Contato efetivo',
    what: 'Proporção dos devedores do braço com contato efetivo (pessoa certa) no período de observação.',
    how: 'Devedores do braço com contato efetivo entre a inscrição e o horizonte, divididos pelos devedores do braço.',
    use: 'Bom para comparar canais e horários. Mais contato não garante mais pagamento: confira as métricas de acordo e pagamento.',
    screens: ['campaign.experiment'],
  },
  {
    id: 'campaign.experiment.reply',
    title: 'Resposta',
    what: 'Proporção dos devedores do braço que responderam a alguma mensagem.',
    how: 'Devedores do braço com resposta no período de observação, divididos pelos devedores do braço.',
    use: 'Indicador rápido para comparar templates. Confirme com acordo e pagamento antes de adotar a variante.',
    screens: ['campaign.experiment'],
  },
  {
    id: 'campaign.experiment.agreement',
    title: 'Acordo',
    what: 'Proporção dos devedores do braço com acordo formalizado no período de observação.',
    how: 'Devedores do braço com acordo entre a inscrição e o horizonte, divididos pelos devedores do braço, inclusive no grupo de controle, que não recebe nenhuma ação.',
    use: 'A diferença para o controle mostra quantos acordos a campanha realmente gerou.',
    screens: ['campaign.experiment'],
  },
  {
    id: 'campaign.experiment.payment',
    title: 'Pagamento confirmado',
    what: 'Proporção dos devedores do braço que pagaram algo no período de observação, líquido de estornos.',
    how: 'Devedores do braço com valor pago líquido maior que zero, divididos pelos devedores do braço.',
    use: 'É a métrica mais próxima do objetivo da cobrança. Prefira-a como métrica principal quando a amostra permitir.',
    screens: ['campaign.experiment'],
  },
  {
    id: 'campaign.experiment.recovered-per-debtor',
    title: 'Valor recuperado por devedor',
    what: 'Valor médio recuperado por devedor do braço, líquido de estornos.',
    how: 'Soma do valor pago no período de observação dividida por todos os devedores do braço (inclusive os que não pagaram).',
    use: 'Combina taxa de pagamento e valor pago. Uma variante com desconto pode ter mais pagamentos e menos valor por devedor.',
    screens: ['campaign.experiment'],
  },
];
