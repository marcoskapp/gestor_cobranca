import { IndicatorHelp } from '../indicator.models';

/**
 * Indicadores de pagamentos e conciliação: valores do pagamento (alocado, sobra, a realocar) e
 * indicadores do lote de extrato. Definições: docs/tecnico/desenho/pagamentos.md §6 (alocação) e §8
 * (conciliação) e docs/negocio/layout-extrato-conciliacao.md; cálculo em `payment/domain` e
 * `features/payment/data/payment.rules.ts` (taxas do lote).
 */
export const INDICATORS: readonly IndicatorHelp[] = [
  {
    id: 'payment.detail.amount',
    title: 'Valor pago',
    what: 'Valor que o devedor pagou neste pagamento.',
    how: 'Valor confirmado pelo provedor, pelo extrato ou por quem informou e aprovou o pagamento.',
    use: 'Compare com o alocado: o que não couber no destino vira sobra e precisa ser realocado ou devolvido.',
    screens: ['payment.detail'],
  },
  {
    id: 'payment.detail.allocated',
    title: 'Alocado',
    what: 'Parte do pagamento aplicada às parcelas do acordo ou aos títulos do devedor.',
    how: 'Soma das alocações do pagamento; cada alocação baixa um destino e é a unidade que entra na recuperação e na comissão.',
    use: 'Só o alocado conta como recuperado para o credor e para a comissão.',
    screens: ['payment.detail'],
  },
  {
    id: 'payment.detail.unallocated',
    title: 'Sobra',
    what: 'Parte do pagamento que não coube em nenhum destino (acordo encerrado, destino já quitado ou valor acima do saldo).',
    how: 'Valor pago menos o alocado.',
    use: 'Sobra parada é dinheiro do devedor sem destino: realoque para outro acordo ou título, ou devolva, na tela de sobras.',
    screens: ['payment.detail', 'payment.reallocation'],
  },
  {
    id: 'payment.detail.pending-reallocation',
    title: 'A realocar',
    what: 'Valor deste pagamento aguardando realocação: a sobra mais o que foi liberado de um acordo sem ser aplicado.',
    how: 'Sobra ainda não realocada mais os valores liberados de alocações a acordo que não foram aplicados.',
    use: 'Realoque com justificativa o quanto antes: enquanto não for realocado, o valor não aparece como recuperação de nenhum título.',
    screens: ['payment.detail', 'payment.reallocation'],
  },
  {
    id: 'payment.statement.lines',
    title: 'Linhas lidas',
    what: 'Quantidade de lançamentos lidos do arquivo de extrato ou retorno bancário.',
    how: 'Todas as linhas válidas do arquivo, créditos e débitos.',
    use: 'Confira com o total de lançamentos do extrato do banco para ter certeza de que o arquivo veio completo.',
    screens: ['payment.statement'],
  },
  {
    id: 'payment.statement.credits',
    title: 'Créditos tratados',
    what: 'Linhas de entrada de dinheiro que o sistema tentou conciliar.',
    how: 'Linhas lidas menos as ignoradas (débitos e transações já importadas em outro arquivo).',
    use: 'É a base das taxas de conciliação e de divergência do lote.',
    screens: ['payment.statement'],
  },
  {
    id: 'payment.statement.reconciled',
    title: 'Conciliadas',
    what: 'Créditos do extrato ligados a um pagamento já confirmado no sistema.',
    how: 'Linhas cujo identificador da transação, referência da cobrança ou pagamento correspondem a um pagamento confirmado com o mesmo valor e data dentro da tolerância.',
    use: 'Quanto maior a taxa de conciliação (conciliadas mais confirmações sobre os créditos), menos trabalho manual e mais rápida a prestação de contas.',
    screens: ['payment.statement'],
  },
  {
    id: 'payment.statement.confirmed',
    title: 'Confirmaram pagamento',
    what: 'Créditos do extrato que eram de uma cobrança emitida e ainda não confirmada: o próprio extrato confirmou o pagamento.',
    how: 'Linhas com a referência de uma cobrança sem pagamento: o sistema cria o pagamento, aplica ao destino e concilia.',
    use: 'Mostra pagamentos que só foram conhecidos pelo extrato (por exemplo, sem aviso do provedor). É normal; acompanhe se o volume crescer.',
    screens: ['payment.statement'],
  },
  {
    id: 'payment.statement.divergent',
    title: 'Divergentes',
    what: 'Créditos do extrato que não puderam ser conciliados automaticamente e foram para a fila de divergências.',
    how: 'Linhas sem correspondência ou com diferença de valor, duplicidade ou devolução parcial. A taxa de divergência divide pelos créditos tratados.',
    use: 'Cada divergência pode ser um pagamento sem baixa: resolva na fila de divergências antes do fechamento da prestação de contas.',
    screens: ['payment.statement', 'payment.divergences'],
  },
  {
    id: 'payment.statement.ignored',
    title: 'Ignoradas',
    what: 'Linhas do extrato que não geram baixa: débitos e transações já importadas em outro arquivo.',
    how: 'Débitos mais linhas reconhecidas como repetidas pela impressão digital da transação.',
    use: 'Muitas repetidas indicam arquivos com períodos sobrepostos; não há baixa em dobro, mas combine com o banco o período de cada arquivo.',
    screens: ['payment.statement'],
  },
  {
    id: 'payment.statement.preview',
    title: 'Prévia do arquivo',
    what: 'Resumo do arquivo antes de importar: linhas, créditos, total creditado e débitos.',
    how: 'Leitura do arquivo no navegador, sem gravar nada; débitos são ignorados na conciliação.',
    use: 'Confira o total creditado com o extrato do banco antes de importar: diferença indica arquivo errado ou incompleto.',
    screens: ['payment.reconciliation'],
  },
];
