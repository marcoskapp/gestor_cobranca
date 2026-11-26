import { IndicatorHelp } from '../indicator.models';

/**
 * Indicadores do Início do portal do credor (fatia 7), de `GET /api/creditor/v1/overview` e `/awaiting`, sempre só do
 * credor da sessão. Definições: docs/negocio/indicadores.md §10 e docs/negocio/portal-credor.md §3.
 */
export const INDICATORS: readonly IndicatorHelp[] = [
  {
    id: 'creditor.home.agreements',
    title: 'Acordos em dia',
    what: 'A parte dos acordos em vigor (formalizados ou em pagamento) que não tem nenhuma parcela vencida e não paga na data de corte. É o número principal do Início.',
    how: 'Acordos em vigor sem parcela vencida e não paga, divididos por todos os acordos em vigor do credor, na data de corte. Parcela paga só em parte conta como vencida; acordo com pagamento informado ainda em conferência pela assessoria conta como em dia. Abaixo de 80% o cartão fica em alerta.',
    use: 'Mostra se os acordos feitos estão sendo cumpridos, que é o que traz o dinheiro. Em alerta, converse com a assessoria sobre os acordos com parcela vencida e as condições oferecidas.',
    screens: ['creditor.home'],
  },
  {
    id: 'creditor.home.balance',
    title: 'Saldo em cobrança',
    what: 'O saldo em aberto dos seus títulos que estão em cobrança com a assessoria, na data de corte.',
    how: 'Soma do saldo em aberto dos títulos em cobrança ou suspensos que ainda têm saldo, na data de referência do cálculo. Títulos pagos, encerrados ou devolvidos não entram.',
    use: 'Dá o tamanho da carteira que a assessoria ainda trabalha. Compare com o recuperado no período para ter ideia do ritmo de recuperação.',
    screens: ['creditor.home'],
  },
  {
    id: 'creditor.home.recovered',
    title: 'Recuperado no período',
    what: 'Quanto a cobrança trouxe no período escolhido (este mês, mês passado, últimos 3 meses ou este ano), com a variação sobre o período anterior de mesma duração.',
    how: 'Pagamentos confirmados no período, pela data do pagamento, menos os estornos, incluindo os pagos direto a você que a assessoria confirmou. A parte paga direto a você aparece separada, mas já está dentro do total. A variação compara com o mesmo trecho do período anterior.',
    use: 'Acompanhe a tendência: queda forte de um período para outro merece conversar com a assessoria sobre a carteira. O valor que entra no repasse é o do demonstrativo do fechamento.',
    screens: ['creditor.home'],
  },
  {
    id: 'creditor.home.transfer',
    title: 'Repasse estimado',
    what: 'O valor que, pelo cálculo de agora, a assessoria deve repassar a você no fechamento do período em aberto, ou que você deve pagar a ela.',
    how: 'O recebido pela assessoria desde o início do período em aberto, menos estornos, honorários retidos, comissão, tarifas e despesas, mais ou menos os ajustes. O texto abaixo do valor diz desde quando o período está aberto e quem paga quem. Não muda com o período escolhido no Início.',
    use: 'Serve para planejar o caixa. O valor definitivo é o do demonstrativo enviado no fechamento, que você aceita ou contesta.',
    screens: ['creditor.home', 'creditor.statements'],
  },
  {
    id: 'creditor.home.awaiting',
    title: 'Aguardando você',
    what: 'Quantos pedidos dependem de uma resposta do seu time agora: contestações, demonstrativos e pagamentos recusados para informar de novo.',
    how: 'Soma das contestações em que a assessoria aguarda a sua posição, dos demonstrativos enviados ainda sem aceite nem contestação (prazo de 10 dias do envio, só lembrete) e dos pagamentos informados recusados nos últimos 30 dias. "Vence logo" é o prazo de hoje até 2 dias.',
    use: 'Responda primeiro os de prazo vencido e os que vencem em até 2 dias. Sem a sua resposta, a assessoria decide com o que tem.',
    screens: ['creditor.home'],
  },
  {
    id: 'creditor.home.monthly',
    title: 'Recuperado nos últimos 6 meses',
    what: 'O recuperado em cada um dos últimos 6 meses, o mês corrente incluído (até a data de corte).',
    how: 'Para cada mês, os pagamentos confirmados pela data do pagamento, menos os estornos, incluindo os pagos direto a você. A tabela mostra também a parte paga direto a você em cada mês. Não muda com o período escolhido.',
    use: 'Mostra a tendência da recuperação mês a mês. O mês corrente ainda está em andamento: compare-o com cuidado.',
    screens: ['creditor.home'],
  },
  {
    id: 'creditor.home.by-portfolio',
    title: 'Recuperado por carteira',
    what: 'De onde veio o recuperado no período escolhido: as 5 carteiras com mais recuperado e "Outros".',
    how: 'O mesmo recuperado do cartão "Recuperado no período", separado pela carteira do título pago. "Outros" soma as demais carteiras e o que foi recebido sem carteira.',
    use: 'Ajuda a ver quais carteiras puxam o resultado e quais pedem atenção na conversa com a assessoria.',
    screens: ['creditor.home'],
  },
];
