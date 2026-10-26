import { ModuleManual } from '../manual.models';

export const MANUAL: ModuleManual = {
  id: 'importacao',
  overview: [
    'A importação é a porta de entrada das dívidas. O credor envia uma planilha (CSV ou XLSX) no layout padrão ou no layout combinado com ele, ou os registros chegam por integração.',
    'Antes de gravar, o arquivo passa pela conferência: você confirma como os valores foram lidos, compara os totais com a sua planilha e decide sobre valores fora do comum. Nada é gravado até a confirmação final.',
    'Gravado o lote, devedores e títulos são criados ou atualizados e os casos de cobrança são abertos automaticamente para os títulos em aberto.',
  ],
  connections: [
    { module: 'administracao', text: 'Recebe: o credor e a carteira que receberão os títulos precisam estar cadastrados.' },
    { module: 'cobranca', text: 'Entrega: devedores, títulos e contatos, que abrem ou atualizam os casos de cobrança.' },
    { module: 'compliance', text: 'Entrega: contatos importados passam pelas restrições e pela política de contato antes de qualquer uso.' },
    { module: 'paineis', text: 'Entrega: falhas e tempo de processamento das importações aparecem no painel de confiabilidade.' },
  ],
  rules: [
    'Nada é gravado até você conferir e confirmar; a conferência fica salva e pode ser retomada.',
    'Divergência entre os totais informados e os lidos bloqueia a importação.',
    'Importações de valor alto pedem aprovação de outra pessoa; quem envia não aprova a própria carga.',
    'Reenviar o mesmo arquivo não duplica títulos; arquivo já importado exige justificativa.',
    'Ocorrências mostram os dados pessoais mascarados; o relatório pode ser devolvido ao credor.',
  ],
  screens: [
    {
      id: 'ingestion.batches',
      title: 'Importação de carteiras',
      objective: 'Acompanhe os arquivos recebidos dos credores e comece uma nova importação, que só grava depois da sua conferência.',
      purpose: [
        'Lista os lotes recebidos, com progresso e resultado, e as conferências em andamento ou aguardando aprovação.',
        'Daqui você baixa o modelo do layout padrão para enviar ao credor e inicia uma nova importação.',
      ],
      profiles: ['ANALISTA_CARTEIRA', 'GESTOR', 'INTEGRACAO', 'AUDITOR'],
      inputs: ['Arquivos enviados pela equipe e registros recebidos por integração.', 'Credores e carteiras cadastrados.'],
      outputs: ['Lotes gravados, que criam ou atualizam devedores, títulos e casos.'],
      related: [
        { screen: 'ingestion.review-start', why: 'Começar uma nova importação.' },
        { screen: 'ingestion.layouts', why: 'Ajustar como as colunas do credor são lidas.' },
        { screen: 'organization.portfolios', why: 'Conferir a carteira que recebe os títulos.' },
      ],
      terms: ['lote-de-importacao', 'conferencia', 'carteira'],
    },
    {
      id: 'ingestion.review-start',
      title: 'Nova importação',
      objective: 'Escolha a carteira, o layout e o arquivo do credor para começar a conferência antes de gravar.',
      purpose: ['Primeiro passo da importação. Em seguida a conferência mostra como o arquivo foi lido, sem gravar nada.'],
      profiles: ['ANALISTA_CARTEIRA', 'GESTOR'],
      inputs: ['Arquivo CSV ou XLSX do credor.', 'Carteira de destino e layout do credor.'],
      outputs: ['Uma conferência em andamento, que você pode retomar depois.'],
      related: [
        { screen: 'ingestion.review', why: 'Os passos da conferência.' },
        { screen: 'ingestion.layouts', why: 'Cadastrar o layout de um credor novo.' },
      ],
      terms: ['layout', 'conferencia'],
    },
    {
      id: 'ingestion.review',
      title: 'Conferência da importação',
      objective: 'Confira formato dos valores, linhas, totais da planilha e alertas antes de gravar o arquivo.',
      purpose: [
        'Evita erros caros de carga: um valor "1.000" lido como um real em vez de mil, linhas faltando ou valores fora do padrão da carteira.',
        'Você avança e volta entre os passos; o andamento fica salvo. Ao final, confere o impacto e confirma.',
      ],
      profiles: ['ANALISTA_CARTEIRA', 'GESTOR'],
      inputs: ['O arquivo enviado em "Nova importação".', 'A soma e a contagem da sua planilha, que você informa.'],
      outputs: ['Lote gravado ou enviado para aprovação de outra pessoa, conforme o valor.'],
      related: [
        { screen: 'ingestion.batches', why: 'Acompanhar o lote depois de confirmar.' },
        { screen: 'ingestion.batch-detail', why: 'Ocorrências por linha do lote.' },
      ],
      terms: ['conferencia', 'quatro-olhos'],
    },
    {
      id: 'ingestion.batch-detail',
      title: 'Lote de importação',
      objective: 'Resultado de um lote: contadores, ocorrências por linha e, quando cabe, importar a prévia ou reprocessar.',
      purpose: [
        'Mostra quantas linhas foram lidas, aceitas e recusadas e quantos devedores e títulos foram criados ou atualizados.',
        'Baixe o relatório de ocorrências para devolver ao credor as linhas que precisam de correção.',
      ],
      profiles: ['ANALISTA_CARTEIRA', 'GESTOR', 'INTEGRACAO', 'AUDITOR'],
      inputs: ['Processamento do arquivo conferido ou do envio por integração.'],
      outputs: ['Títulos e devedores gravados; relatório de ocorrências para o credor.'],
      related: [
        { screen: 'ingestion.batches', why: 'Todos os lotes.' },
        { screen: 'debt.titles', why: 'Ver os títulos gravados.' },
      ],
      terms: ['lote-de-importacao', 'titulo', 'devedor'],
    },
    {
      id: 'ingestion.layouts',
      title: 'Layouts de importação',
      objective: 'Defina como as colunas do arquivo de cada credor correspondem aos campos do sistema.',
      purpose: ['Quando o credor não usa o layout padrão, um layout próprio evita retrabalho: a correspondência fica salva e é usada nas próximas importações.'],
      profiles: ['ANALISTA_CARTEIRA', 'GESTOR', 'INTEGRACAO'],
      inputs: ['O arquivo de exemplo do credor.'],
      outputs: ['Layout disponível em "Nova importação".'],
      related: [{ screen: 'ingestion.review-start', why: 'Usar o layout numa importação.' }],
      terms: ['layout'],
    },
  ],
  terms: ['lote-de-importacao', 'conferencia', 'layout', 'carteira', 'titulo', 'devedor'],
};
