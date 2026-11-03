import { IndicatorHelp } from '../indicator.models';

/**
 * Indicadores da importação de carteiras: validação das linhas, totais de controle, alertas de valor
 * (ticket médio), impacto da importação e contadores do lote e da Importação PDF. Definições:
 * docs/tecnico/desenho/importacao.md §3 (totais), §4 (ticket médio e alertas) e §5 (impacto),
 * docs/negocio/layout-importacao.md §9 (PDF); cálculo no módulo `ingestion` (`ControlTotals`,
 * `LogScaleStats`, `AmountOutlierPolicy`).
 */
export const INDICATORS: readonly IndicatorHelp[] = [
  {
    id: 'ingestion.review.validation',
    title: 'Resultado da validação',
    what: 'Quantas linhas do arquivo foram lidas, quantas estão válidas, quantas têm erro e quantos avisos foram gerados.',
    how: 'Cada linha de dados é conferida contra o layout (campos obrigatórios, datas, valores, documento). Linha com erro não é importada; aviso não impede, mas merece leitura.',
    use: 'Muitos erros do mesmo tipo indicam problema no arquivo inteiro (coluna trocada, formato de valor): corrija na origem e envie de novo em vez de excluir linha a linha.',
    screens: ['ingestion.review'],
  },
  {
    id: 'ingestion.review.control-totals',
    title: 'Totais de controle',
    what: 'Comparação entre os totais que você digitou da sua planilha (linhas e somas de valores) e os totais que o sistema leu do arquivo.',
    how: 'O sistema soma todas as linhas de dados do arquivo, inclusive as com erro, com o formato de valor escolhido. Quantidade precisa bater exatamente; valores aceitam diferença de até um centavo.',
    use: 'Se não bater, a importação fica bloqueada. Diferença de 10, 100 ou 1.000 vezes quase sempre é separador decimal trocado: volte ao passo do formato dos valores.',
    screens: ['ingestion.review'],
  },
  {
    id: 'ingestion.review.breakdown',
    title: 'Totais do arquivo por grupo',
    what: 'Linhas, principal e saldo do arquivo separados por faixa de vencimento, situação informada, produto e validade da linha.',
    how: 'As mesmas somas dos totais de controle, quebradas por grupo.',
    use: 'Compare com os subtotais da sua planilha para achar onde está a diferença quando os totais não batem.',
    screens: ['ingestion.review'],
  },
  {
    id: 'ingestion.review.expected-range',
    title: 'Faixa esperada (ticket médio)',
    what: 'Intervalo de valores considerado normal para o título, com base no histórico do credor ou no próprio arquivo.',
    how: 'O sistema usa a mediana dos valores já carregados do credor (na mesma carteira e produto, quando há pelo menos 30 títulos) ou do próprio arquivo, com uma medida de dispersão que não é distorcida pelos extremos. Valor muito acima ou muito abaixo dessa faixa vira alerta.',
    use: 'Um valor fora da faixa pode ser verdadeiro (dívida grande) ou erro de digitação ou de separador decimal. Confirme se estiver correto ou exclua a linha do lote.',
    screens: ['ingestion.review'],
  },
  {
    id: 'ingestion.review.alerts',
    title: 'Alertas de valor',
    what: 'Sinais de valor suspeito no arquivo, por tipo: provável separador decimal, valor fora do ticket médio, saldo muito acima do principal, vencimentos improváveis e outros.',
    how: 'Cada tipo tem uma regra; os de escala (separador decimal, mudança de escala do arquivo, padrão de centavos) têm prioridade máxima. Mostra quantos estão abertos e quantos já foram revisados.',
    use: 'Alertas de escala precisam ser revisados antes de importar, e há um limite de alertas abertos. Muitos alertas do mesmo tipo indicam problema sistemático: revise o formato dos valores.',
    screens: ['ingestion.review'],
  },
  {
    id: 'ingestion.review.impact',
    title: 'Impacto da importação',
    what: 'O que vai acontecer ao importar: linhas a importar, títulos novos, títulos já existentes, títulos que ficam suspensos e a soma do principal.',
    how: 'Calculado sobre as linhas válidas e não excluídas na conferência, comparando com os títulos já cadastrados do credor; linhas com erro e excluídas ficam fora.',
    use: 'Confira se a quantidade de títulos novos e a soma do principal fazem sentido para o que o credor enviou antes de importar ou pedir aprovação.',
    screens: ['ingestion.review'],
  },
  {
    id: 'ingestion.batch.principal',
    title: 'Principal do lote',
    what: 'Soma do valor principal das linhas validadas ou gravadas neste lote.',
    how: 'Soma do valor principal das linhas aceitas, com o formato de valor aplicado na leitura.',
    use: 'Compare com o total que o credor informou para o arquivo. Diferença grande pede rever a conferência e a leitura dos valores.',
    screens: ['ingestion.batch-detail'],
  },
  {
    id: 'ingestion.pdf.summary',
    title: 'Resumo da leitura do PDF',
    what: 'Títulos encontrados no PDF, quantos serão importados, devedores, valor original, saldo e títulos com erro.',
    how: 'Contagens e somas do mapa de dados escrito a partir do PDF, depois das suas decisões de excluir ou confirmar títulos.',
    use: 'Confira os totais com o PDF original (ou com o total informado pelo credor) antes de concluir; títulos de confiança baixa precisam ser confirmados um a um.',
    screens: ['ingestion.pdf-import'],
  },
];
