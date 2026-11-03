import { IndicatorHelp } from '../indicator.models';

/**
 * Indicadores da integração dos credores (consumo e faturamento do ciclo). Definições:
 * docs/negocio/api-publica.md ("Consumo e faturamento") e decisões do billing; cálculo em
 * `publicapi/application/service/BillingService.java` e `PriceCalculator`.
 */
export const INDICATORS: readonly IndicatorHelp[] = [
  {
    id: 'publicapi.billing.amount',
    title: 'Valor até agora',
    what: 'Quanto o credor deve pela integração no ciclo em andamento, até este momento.',
    how: 'Mensalidade do ciclo (proporcional no ciclo de transição) mais o consumo acima das franquias, pelas condições de cobrança do contrato. Sem condições cadastradas, o valor é zero.',
    use: 'Acompanhe o crescimento do faturamento sem esperar o fechamento. Valor subindo rápido merece conversar com o credor sobre o volume.',
    screens: ['settlement.position', 'publicapi.billing'],
  },
  {
    id: 'publicapi.billing.projection',
    title: 'Projeção no fechamento',
    what: 'Estimativa do valor do ciclo no dia do fechamento, se o ritmo de uso continuar.',
    how: 'O consumo até agora mais a média diária do ciclo vezes os dias que faltam, calculado com as mesmas condições do contrato (mensalidade, franquias e excedente).',
    use: 'Antecipe a conversa com o credor quando a projeção passar muito do valor habitual ou da franquia contratada.',
    screens: ['settlement.position'],
  },
  {
    id: 'publicapi.usage.billable-requests',
    title: 'Chamadas cobráveis',
    what: 'Chamadas da integração do credor que deram certo e entram na cobrança.',
    how: 'Só respostas de sucesso contam. Chamadas recusadas por excesso, erros do credor e falhas da plataforma não são cobradas, nem os dados trafegados nelas.',
    use: 'Base da cobrança por chamada. Diferença grande para o total de chamadas mostra integração com erros: ajude o credor a corrigir.',
    screens: ['publicapi.usage', 'settlement.position'],
  },
  {
    id: 'publicapi.usage.requests',
    title: 'Requisições',
    what: 'Total de chamadas feitas pela integração do credor no período, com as cobráveis ao lado.',
    how: 'Contagem de todas as chamadas recebidas, de qualquer resultado, no período e tokens escolhidos.',
    use: 'Mostra o volume de uso. Compare com as cobráveis para ver quanto do tráfego falhou.',
    screens: ['publicapi.usage'],
  },
  {
    id: 'publicapi.usage.throttled',
    title: 'Limitadas',
    what: 'Chamadas recusadas porque o credor passou do limite de chamadas por minuto.',
    how: 'Contagem das recusas por excesso no período. Não são cobradas.',
    use: 'Valores altos indicam integração mal dimensionada: oriente o credor a espaçar as chamadas ou a enviar em lotes.',
    screens: ['publicapi.usage'],
  },
  {
    id: 'publicapi.usage.errors',
    title: 'Erros (não cobrados)',
    what: 'Chamadas que falharam por erro do credor (dados inválidos, sem permissão) ou por falha da plataforma.',
    how: 'Contagem separada dos erros do credor e das falhas da plataforma no período. Nenhum deles é cobrado.',
    use: 'Erros do credor pedem ajuste na integração dele; falhas da plataforma pedem acionar o suporte.',
    screens: ['publicapi.usage'],
  },
  {
    id: 'publicapi.usage.data',
    title: 'Dados trafegados',
    what: 'Volume de dados recebidos do credor e enviados a ele pela integração.',
    how: 'No consumo, o tamanho dos dados das chamadas. Na cobrança, o total do ciclo de cada token em MB inteiros (1 MB = 1.048.576 bytes), arredondado para cima e só das chamadas de sucesso.',
    use: 'Envios grandes e frequentes encarecem o ciclo. Consultas paginadas e envio só do que mudou reduzem o volume.',
    screens: ['publicapi.usage', 'settlement.position'],
  },
  {
    id: 'publicapi.usage.records',
    title: 'Registros',
    what: 'Itens processados pela integração: títulos importados, contatos, pagamentos e outros.',
    how: 'Contagem dos registros processados nas chamadas do período (ou do ciclo, na posição do credor).',
    use: 'Mostra o trabalho real feito pela integração; é base de cobrança quando o contrato prevê preço por registro.',
    screens: ['publicapi.usage', 'settlement.position'],
  },
];
