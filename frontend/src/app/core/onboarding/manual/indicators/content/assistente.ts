import { IndicatorHelp } from '../indicator.models';

/**
 * Indicadores da qualidade do assistente de IA: transferências, reclamações, custo, revisões,
 * latência, quebra precoce e Pix na conversa. Definições: docs/negocio/assistente-ia.md e
 * docs/tecnico/desenho/assistente.md ("Qualidade"); cálculo em
 * `assistant/application/service/AssistantReviewService.java` e `JdbcQualityStatistics`.
 */
export const INDICATORS: readonly IndicatorHelp[] = [
  {
    id: 'assistant.quality.handoff-rate',
    title: 'Transferência para humano',
    what: 'Quanto das conversas atendidas pelo assistente precisou ser passada a uma pessoa.',
    how: 'Transferências do período (por qualquer motivo) divididas pelas conversas atendidas pelo assistente. O gráfico ao lado separa por motivo.',
    use: 'Taxa alta com motivo "fora da alçada" pede revisar a alçada do assistente; com "não entendeu", revisar a política e o roteiro. Transferências por reclamação devem ser poucas.',
    screens: ['assistant.quality'],
  },
  {
    id: 'assistant.quality.complaints',
    title: 'Reclamações',
    what: 'Conversas em que o devedor reclamou ou citou Procon, advogado, processo ou ouvidoria, ou pediu para não ser mais contatado.',
    how: 'Transferências do período cujo motivo é uma reclamação ou pedido de não contato.',
    use: 'É o principal sinal de risco. Qualquer aumento merece ler as conversas na revisão antes de ampliar o uso do assistente na carteira.',
    screens: ['assistant.quality'],
  },
  {
    id: 'assistant.quality.cost-per-agreement',
    title: 'Custo por acordo',
    what: 'Quanto o uso do modelo de IA custou para cada acordo com participação do assistente, e o custo total do período.',
    how: 'Custo do modelo no período (pela tabela de preços configurada, na moeda do provedor) dividido pelos acordos fechados ou preparados pelo assistente.',
    use: 'Compare com o valor recuperado desses acordos e com o custo de um negociador. Custo subindo sem mais acordos indica conversas longas: revise limites e o roteiro.',
    screens: ['assistant.quality'],
  },
  {
    id: 'assistant.quality.reviews',
    title: 'Revisões',
    what: 'Nota média das conversas do assistente avaliadas por pessoas (de 1 a 5), quantas ficaram fora da política e quantas aguardam revisão.',
    how: 'Média das notas das revisões registradas no período; "fora da política" conta as avaliadas como em desacordo com as regras.',
    use: 'Nota baixa ou conversas fora da política impedem avançar de fase. Mantenha a fila de revisão em dia para a amostra ser representativa.',
    screens: ['assistant.quality', 'assistant.reviews'],
  },
  {
    id: 'assistant.quality.latency',
    title: 'Latência média',
    what: 'Tempo médio que o assistente leva para responder, com o volume de texto processado pelo modelo.',
    how: 'Média do tempo de resposta do modelo por turno no período; o volume é medido em tokens (pedaços de texto) de entrada e de saída.',
    use: 'Respostas lentas fazem o devedor desistir. Latência subindo pede verificar o provedor do modelo ou o tamanho do contexto enviado.',
    screens: ['assistant.quality'],
  },
  {
    id: 'assistant.quality.origins',
    title: 'Acordos por origem',
    what: 'Acordos do período separados por quem negociou e fechou: negociador, negociador com copiloto, assistente sozinho, assistente com confirmação humana e outros.',
    how: 'Contagem dos acordos formalizados no período pela origem registrada na formalização.',
    use: 'Mostra quanto do resultado já vem do assistente e como a equipe usa o copiloto.',
    screens: ['assistant.quality'],
  },
  {
    id: 'assistant.quality.early-break',
    title: 'Quebra precoce: assistente × humanos',
    what: 'Proporção dos acordos que quebraram nos primeiros dias (padrão 60), comparando os acordos com o assistente e os só de humanos.',
    how: 'Acordos formalizados no período e quebrados dentro da janela, divididos por todos os acordos formalizados no período, por grupo. Acordos recentes ainda não completaram a janela, então a taxa do período mais recente tende a ser menor.',
    use: 'Critério de avanço de fase: a quebra com o assistente deve ser igual ou menor que a dos humanos. Compare períodos já maduros (formalizados há mais que a janela).',
    screens: ['assistant.quality'],
  },
  {
    id: 'assistant.quality.guardrails',
    title: 'Guardrails acionados',
    what: 'Vezes em que uma proteção automática barrou ou corrigiu uma resposta do assistente, por tipo.',
    how: 'Contagem das proteções acionadas nos turnos do período (por exemplo, valor que não veio do cálculo, condição fora da política, dado pessoal).',
    use: 'Proteções funcionando evitam erro com o devedor. Um tipo crescendo indica ajuste necessário no roteiro ou na política.',
    screens: ['assistant.quality'],
  },
  {
    id: 'assistant.quality.pix',
    title: 'Pix na conversa',
    what: 'Pix gerados pelo assistente na conversa, entregues, sugeridos, pagos (e pagos em até 24 horas) e as recusas por motivo.',
    how: 'Contagens do período; a conversão é Pix pagos divididos pelos gerados.',
    use: 'Conversão baixa indica que o devedor não estava pronto para pagar: reveja quando o assistente oferece o Pix. Recusas mostram por que o Pix não pôde ser gerado.',
    screens: ['assistant.quality'],
  },
];
