import { ModuleManual } from '../manual.models';

/**
 * Portal do credor: o que a pessoa do credor vê e faz no endereço próprio da assessoria. Fica no manual do app interno
 * para quem atende o credor saber o que ele vê; o portal ainda não abre este manual (o "i" dos números do portal não
 * leva ao "Saiba mais"). Desenho em docs/negocio/portal-credor.md.
 */
export const MANUAL: ModuleManual = {
  id: 'portal-credor',
  overview: [
    'O portal do credor é o endereço com a marca da assessoria em que as pessoas do credor acompanham o próprio resultado: os acordos em dia, o saldo em cobrança, o recuperado e o repasse do período, os demonstrativos enviados, as contestações em que a assessoria pediu a posição do credor e os pagamentos recebidos diretamente.',
    'Cada pessoa entra com senha e autenticador, pelo convite feito na ficha do credor. O perfil define o que ela faz: o Financeiro aceita ou contesta demonstrativos, a Gestão de recuperação e o Jurídico respondem contestações, a Gestão de recuperação e o Financeiro informam pagamentos, e a Consulta só acompanha.',
    'O portal mostra só os dados do próprio credor, sem conversas, contatos do devedor nem dados internos da assessoria. Cada ação fica registrada com quem fez e quando.',
  ],
  connections: [
    { module: 'administracao', text: 'Recebe: o portal liberado e as pessoas convidadas na ficha do credor.' },
    { module: 'prestacao-de-contas', text: 'Recebe: os demonstrativos enviados; devolve o aceite ou a contestação do credor.' },
    { module: 'cobranca', text: 'Recebe: os pedidos de posição das contestações; devolve a resposta com documentos.' },
    { module: 'pagamentos', text: 'Devolve: os pagamentos que o devedor fez direto ao credor, para a assessoria conferir.' },
  ],
  rules: [
    'A pessoa do credor vê só o próprio credor; nada de outro credor aparece nem nas contagens.',
    'Quem decide a contestação é a assessoria: o credor dá a posição e envia documentos.',
    'Demonstrativo enviado não muda: o credor aceita (com o código do autenticador) ou contesta, e a correção entra como ajuste no período seguinte.',
    'Pagamento informado pelo credor só baixa os títulos depois que a assessoria confere o comprovante.',
  ],
  screens: [
    {
      id: 'creditor.home',
      title: 'Início do portal do credor',
      objective: 'Ver de uma vez os acordos em dia, o saldo, o recuperado e o repasse do credor e o que aguarda a resposta dele.',
      purpose: [
        '"Acordos em dia" é o número principal: a parte dos acordos em vigor sem parcela vencida e não paga, em alerta abaixo de 80%. Ao lado ficam o saldo em cobrança, o recuperado no período escolhido (este mês, mês passado, últimos 3 meses ou este ano) com a variação sobre o período anterior e a parte paga direto ao credor, e o repasse estimado do período em aberto, que só fica definitivo no demonstrativo do fechamento e não muda com o período escolhido. "Dados até" mostra a data de corte dos números; o período escolhido fica no endereço da página.',
        '"Aguardando você" junta as contestações com pedido da assessoria, os demonstrativos enviados sem resposta (prazo de 10 dias do envio, só lembrete) e os pagamentos informados recusados nos últimos 30 dias, com os vencidos primeiro. Os que vencem em até 2 dias contam do dia de hoje. Cada item abre a ficha; o pagamento recusado abre a lista de pagamentos com ele em destaque. "Ver todas" mostra o resto no próprio cartão, e o rodapé leva às listas que têm itens. Quem não responde pelo credor (perfil Consulta, por exemplo) vê o bloco como "Aguardando o seu time", com quem responde cada item.',
        'Os gráficos mostram o recuperado nos últimos 6 meses e por carteira (as 5 maiores e "Outros"), cada um com o resumo escrito e "Ver tabela". Se uma parte dos números estiver indisponível, um aviso acima dos cartões diz o que faltou, com "Tentar novamente", e os cartões e gráficos dela mostram "Indisponível agora"; o resto continua valendo.',
      ],
      profiles: ['CREDOR'],
      inputs: ['Indicadores do credor (acordos, saldo, recuperado por mês e por carteira), período em aberto da prestação de contas e as pendências do credor.'],
      outputs: ['Atalhos para a ficha de cada pendência, para as listas e para "Minha atividade".'],
      related: [
        { screen: 'creditor.statements', why: 'Todos os demonstrativos e o período em aberto.' },
        { screen: 'creditor.disputes', why: 'Contestações por situação.' },
        { screen: 'creditor.payments', why: 'Pagamentos informados e o motivo da recusa.' },
        { screen: 'creditor.activity', why: 'O que você e o seu time fizeram no portal.' },
      ],
      terms: ['portal-do-credor', 'recuperado', 'repasse'],
    },
    {
      id: 'creditor.statements',
      title: 'Prestação de contas (portal do credor)',
      objective: 'Acompanhar o período em aberto e os demonstrativos enviados pela assessoria, do mais recente ao mais antigo.',
      purpose: [
        'O período em aberto é uma estimativa calculada na hora, com o gráfico do recuperado acumulado dia a dia comparado com o período anterior; o valor final sai no demonstrativo do fechamento. Cada demonstrativo enviado leva à ficha, onde o credor confere os arquivos e responde.',
        'A lista mostra o prazo para aceitar ou contestar cada demonstrativo ainda sem resposta (10 dias do envio, só lembrete: vencido, o demonstrativo segue aguardando) e quanto do líquido já foi pago e quanto falta.',
      ],
      profiles: ['CREDOR'],
      inputs: ['Demonstrativos aprovados e enviados ao credor; posição do período em aberto.'],
      outputs: ['Ficha do demonstrativo para conferir e responder.'],
      related: [
        { screen: 'creditor.statement', why: 'Conferir e responder um demonstrativo.' },
        { screen: 'settlement.statement', why: 'O mesmo demonstrativo no lado da assessoria.' },
      ],
      terms: ['demonstrativo', 'repasse'],
    },
    {
      id: 'creditor.statement',
      title: 'Demonstrativo (portal do credor)',
      objective: 'Conferir os valores e os arquivos de um demonstrativo enviado e aceitar ou contestar.',
      purpose: [
        'O download confere o código de integridade de cada arquivo. O aceite pede o código do autenticador e guarda o código de integridade dos arquivos vistos; a contestação pede o motivo.',
        'Enquanto aguarda a resposta, a ficha mostra o prazo para aceitar ou contestar e avisa quando venceu. Nos valores, o medidor mostra quanto do líquido já foi pago.',
      ],
      profiles: ['CREDOR'],
      inputs: ['Demonstrativo enviado pela assessoria, com os arquivos.'],
      outputs: ['Aceite ou contestação registrados no demonstrativo da assessoria.'],
      related: [
        { screen: 'creditor.statements', why: 'Voltar aos demonstrativos.' },
        { screen: 'settlement.statement', why: 'Onde a assessoria vê a resposta.' },
      ],
      terms: ['demonstrativo', 'codigo-de-integridade'],
    },
    {
      id: 'creditor.disputes',
      title: 'Contestações (portal do credor)',
      objective: 'Contestações de devedores em que a assessoria pediu a posição do credor, separadas por situação.',
      purpose: [
        'As abas "Aguardando você", "Em análise", "Encerradas" e "Todas" ficam no endereço da página. O prazo pedido pela assessoria aparece em cada linha, com o aviso quando vence.',
      ],
      profiles: ['CREDOR'],
      inputs: ['Pedidos de posição enviados pela assessoria nas contestações.'],
      outputs: ['Ficha da contestação para ler o pedido e responder.'],
      related: [
        { screen: 'creditor.dispute', why: 'Ler o pedido e responder.' },
        { screen: 'collection.dispute', why: 'A mesma contestação no lado da assessoria.' },
      ],
      terms: ['contestacao', 'portal-do-credor'],
    },
    {
      id: 'creditor.dispute',
      title: 'Contestação (portal do credor)',
      objective: 'Ler o pedido da assessoria, baixar os documentos e responder com a posição do credor.',
      purpose: [
        'A resposta leva a posição (confirma a dívida, o devedor tem razão, em parte, ou só informação), um resumo e até 5 documentos, que passam pela verificação de segurança antes de chegar à assessoria.',
      ],
      profiles: ['CREDOR'],
      inputs: ['Contestação com o pedido da assessoria e os documentos anexados.'],
      outputs: ['Resposta do credor na contestação da assessoria.'],
      related: [
        { screen: 'creditor.disputes', why: 'Voltar às contestações.' },
        { screen: 'collection.dispute', why: 'Onde a assessoria decide.' },
      ],
      terms: ['contestacao', 'codigo-de-integridade'],
    },
    {
      id: 'creditor.payments',
      title: 'Pagamentos informados (portal do credor)',
      objective: 'Informar pagamento que o devedor fez direto ao credor e acompanhar a conferência da assessoria.',
      purpose: [
        'O informe leva os títulos, o valor, a data, o comprovante e o código do autenticador. A assessoria confere e dá baixa ou recusa com o motivo, que aparece na lista.',
        'Quem chega pelo "Aguardando você" ou por "Minha atividade" encontra o pagamento citado em destaque na lista, com a tela já rolada até ele.',
      ],
      profiles: ['CREDOR'],
      inputs: ['Títulos do credor e o comprovante do pagamento.'],
      outputs: ['Pagamento em análise na assessoria; baixa nos títulos depois da aprovação.'],
      related: [
        { screen: 'creditor.home', why: 'Pagamentos recusados aparecem nas pendências.' },
        { screen: 'payment.review', why: 'Onde a assessoria confere o informe.' },
      ],
      terms: ['pagamento-confirmado', 'portal-do-credor'],
    },
    {
      id: 'creditor.activity',
      title: 'Minha atividade (portal do credor)',
      objective: 'Conferir o que a pessoa (e, na Gestão de recuperação e no Compliance, o time do credor) fez no portal em 90 dias.',
      purpose: [
        'Mostra aceites e contestações de demonstrativos, respostas a contestações, pagamentos informados, downloads, entradas e saídas, troca de senha e ativação do autenticador, do mais recente ao mais antigo, com o link para o registro quando o portal abre a ficha. "Carregar mais" traz as ações mais antigas.',
        'A aba "Do seu time" só aparece para quem tem a permissão de ver a atividade do time; consultar o time fica registrado. Ações da assessoria sobre o credor não aparecem aqui.',
      ],
      profiles: ['CREDOR'],
      inputs: ['Registro de auditoria e de acesso das pessoas do credor no portal.'],
      outputs: ['Atalho para a ficha do demonstrativo, da contestação ou para o pagamento citado, em destaque na lista de pagamentos.'],
      related: [
        { screen: 'creditor.home', why: 'Voltar ao Início do portal.' },
        { screen: 'creditor.statements', why: 'Os demonstrativos aceitos ou contestados.' },
      ],
      terms: ['portal-do-credor'],
    },
  ],
  terms: ['portal-do-credor', 'demonstrativo', 'repasse', 'contestacao', 'codigo-de-integridade'],
};
