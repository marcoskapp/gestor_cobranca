import { ScreenGuide } from '../onboarding.models';

/**
 * Telas acompanhadas pelo onboarding. Ao publicar mudança relevante em uma tela: incremente
 * `version` e acrescente um item em `changes` com a mesma versão (o usuário que já conhecia a
 * tela vê o destaque das novidades uma vez).
 */
export const SCREENS: readonly ScreenGuide[] = [
  {
    key: 'app.home',
    version: 4,
    path: '/',
    changes: [
      { version: 2, items: ['Indicadores da operação na página inicial, com atalho para os painéis.'] },
      {
        version: 3,
        items: [
          '"Seu dia": "Próximo caso" (Alt + N), casos com você, retornos de hoje, promessas vencendo e o seu resultado do dia; para o supervisor, as equipes e as filas.',
          'Sem o "Acesso rápido" (o menu já leva a todas as telas) e sem avisos repetidos: o aviso de verificação em duas etapas e o convite do tour só aparecem quando os primeiros passos não os trazem.',
        ],
      },
      { version: 4, items: ['Em "Operação", a fila pendente e o recuperado em 30 dias levam à lista que forma o número (Casos e Razão de comissão), quando o seu perfil abre essas listas.'] },
    ],
  },
  {
    key: 'analytics.operational',
    version: 5,
    path: '/paineis',
    tourId: 'analytics.operational',
    changes: [
      { version: 2, items: ['Ícone "i" ao lado de cada indicador: o que é o número, como é calculado e como usar na decisão, ao passar o mouse, tocar ou com Tab.', 'Tour desta tela refeito: cada passo mostra um indicador e o que fazer quando ele sobe ou cai.'] },
      { version: 3, items: ['Abas sem ícones; no celular, os filtros ficam no botão "Filtros" e os indicadores aparecem logo no início da tela.'] },
      { version: 4, items: ['Os painéis saíram das abas e ficam só no menu, em Painéis; o período escolhido acompanha você de um painel para outro.'] },
      { version: 5, items: ['Números sublinhados levam à lista que forma o número, com o mesmo credor, carteira e período (ex.: casos em aberto → Casos; recuperado → Razão de comissão). Sem a lista no seu perfil, ou com um recorte que a lista não tem, o número fica só como texto.'] },
    ],
  },
  {
    key: 'analytics.recovery',
    version: 4,
    path: '/paineis/recuperacao',
    tourId: 'analytics.recovery',
    changes: [
      { version: 2, items: ['Ícone "i" ao lado de cada indicador: o que é o número, como é calculado e como usar na decisão, ao passar o mouse, tocar ou com Tab.', 'Tour desta tela refeito: cada passo mostra um indicador e o que fazer quando ele sobe ou cai.'] },
      { version: 3, items: ['Abas sem ícones; no celular, os filtros ficam no botão "Filtros" e os indicadores aparecem logo no início da tela.'] },
      { version: 4, items: ['Recuperado, estornos e cada linha por credor, carteira, mês, semana ou dia levam ao Razão de comissão com o mesmo período e recorte (recebimentos, estornos ou os dois). Com filtro de pessoa ou canal não há link: o razão não tem esses recortes.'] },
    ],
  },
  {
    key: 'analytics.conversion',
    version: 3,
    path: '/paineis/conversao',
    tourId: 'analytics.conversion',
    changes: [
      { version: 2, items: ['Ícone "i" ao lado de cada indicador: o que é o número, como é calculado e como usar na decisão, ao passar o mouse, tocar ou com Tab.', 'Tour desta tela refeito: cada passo mostra um indicador e o que fazer quando ele sobe ou cai.'] },
      { version: 3, items: ['Abas sem ícones; no celular, os filtros ficam no botão "Filtros" e os indicadores aparecem logo no início da tela.'] },
    ],
  },
  {
    key: 'analytics.productivity',
    version: 3,
    path: '/paineis/produtividade',
    tourId: 'analytics.productivity',
    changes: [
      { version: 2, items: ['Ícone "i" ao lado de cada indicador: o que é o número, como é calculado e como usar na decisão, ao passar o mouse, tocar ou com Tab.', 'Tour desta tela refeito: cada passo mostra um indicador e o que fazer quando ele sobe ou cai.'] },
      { version: 3, items: ['Abas sem ícones; no celular, os filtros ficam no botão "Filtros" e os indicadores aparecem logo no início da tela.'] },
    ],
  },
  {
    key: 'analytics.disputes',
    version: 3,
    path: '/paineis/contestacoes',
    tourId: 'analytics.disputes',
    changes: [
      { version: 2, items: ['Abas sem ícones; no celular, os filtros ficam no botão "Filtros" e os indicadores aparecem logo no início da tela.'] },
      { version: 3, items: ['Com o período até hoje, "Em aberto agora" e o número em aberto de cada credor levam à fila de contestações filtrada.'] },
    ],
  },
  {
    key: 'analytics.reliability',
    version: 4,
    path: '/paineis/confiabilidade',
    tourId: 'analytics.reliability',
    changes: [
      { version: 2, items: ['Ícone "i" ao lado de cada indicador: o que é o número, como é calculado e como usar na decisão, ao passar o mouse, tocar ou com Tab.', 'Tour desta tela refeito: cada passo mostra um indicador e o que fazer quando ele sobe ou cai.'] },
      { version: 3, items: ['Abas sem ícones; no celular, os filtros ficam no botão "Filtros" e os indicadores aparecem logo no início da tela.'] },
      { version: 4, items: ['Divergências em aberto, abertas no período e por tipo levam à lista de divergências com o mesmo credor e período.'] },
    ],
  },
  { key: 'scoring.models', version: 1, path: '/score/modelos', tourId: 'scoring.models' },
  {
    key: 'scoring.model',
    version: 3,
    path: '/score/modelos/:id',
    tourId: 'scoring.model',
    changes: [
      { version: 2, items: ['Ícone "i" ao lado de cada indicador: o que é o número, como é calculado e como usar na decisão, ao passar o mouse, tocar ou com Tab.'] },
      { version: 3, items: ['"Rejeitar modelo" foi para o menu "Mais ações" (⋯), separado das demais ações; a confirmação com motivo continua.'] },
    ],
  },
  { key: 'scoring.runs', version: 1, path: '/score/execucoes' },
  { key: 'scoring.run', version: 1, path: '/score/execucoes/:id' },
  {
    key: 'scoring.monitoring',
    version: 2,
    path: '/score/monitoramento',
    tourId: 'scoring.monitoring',
    changes: [{ version: 2, items: ['Ícone "i" ao lado de cada indicador: o que é o número, como é calculado e como usar na decisão, ao passar o mouse, tocar ou com Tab.', 'Tour desta tela refeito: cada passo mostra um indicador e o que fazer quando ele sobe ou cai.'] }],
  },
  { key: 'scoring.contact', version: 1, path: '/score/contato', tourId: 'scoring.contact' },
  { key: 'scoring.transparency', version: 1, path: '/score/transparencia' },
  { key: 'scoring.settings', version: 1, path: '/score/parametros', tourId: 'scoring.settings' },
  {
    key: 'admin.users',
    version: 2,
    path: '/admin/usuarios',
    tourId: 'admin.users',
    changes: [
      {
        version: 2,
        items: [
          'Novo usuário sem a permissão de consultar credores: o seletor de credores não aparece e o usuário é criado sem credores (ou com todos, se você tiver todos); o escopo é definido depois, no detalhe do usuário.',
        ],
      },
    ],
  },
  {
    key: 'admin.user-detail',
    version: 4,
    path: '/admin/usuarios/:id',
    changes: [
      { version: 2, items: ['Sem a permissão de consultar credores, o escopo mostra só quantos credores a pessoa acessa e a ação "Alterar escopo de credores" não aparece.'] },
      {
        version: 3,
        items: ['"Alterar escopo de credores": a opção "Acesso a todos os credores" aparece só para quem tem todos; se o usuário já tem todos, ela fica marcada e só quem tem todos pode retirá-la.'],
      },
      { version: 4, items: ['As ações da conta (perfis, escopo, links, MFA, sessões e desativar) ficam no menu "Mais ações" (⋯); desativar fica por último, separado.'] },
    ],
  },
  { key: 'admin.roles', version: 1, path: '/admin/perfis', tourId: 'admin.roles' },
  { key: 'admin.teams', version: 1, path: '/admin/equipes', tourId: 'admin.teams' },
  { key: 'admin.units', version: 1, path: '/admin/unidades' },
  {
    key: 'debt.debtors',
    version: 3,
    path: '/devedores',
    tourId: 'debt.debtors',
    changes: [
      { version: 2, items: ['Empresas mostram o nome fantasia e se são matriz ou filial; "Outras unidades da empresa", na ficha, abre a lista só com as unidades da mesma empresa.'] },
      {
        version: 3,
        items: [
          'Filtro "Com contato novo": devedores que receberam telefone, e-mail ou endereço numa nova carga e ainda não foram tentados por ele.',
          'Aberta pelo relatório da importação, a lista mostra os devedores que receberam contato novo naquela carga; "Ver todos os devedores" sai do filtro.',
        ],
      },
    ],
  },
  // a configuração vem antes da ficha (":id" casaria "contatos-recebidos")
  { key: 'debt.contact-novelty', version: 1, path: '/devedores/contatos-recebidos' },
  {
    key: 'debt.debtor-detail',
    version: 10,
    path: '/devedores/:id',
    tourId: 'debt.debtor-detail',
    changes: [
      { version: 2, items: ['Nova restrição de contato: o campo Credor lista só os credores do devedor, pedido do devedor e descadastro começam valendo para todos os credores e o quadro "Alcance" resume o que será bloqueado antes de salvar.'] },
      { version: 3, items: ['O dossiê de contato passou para o menu "Mais ações" (⋯) no cabeçalho da ficha.'] },
      {
        version: 4,
        items: [
          '"Abrir caso" e "Simular" no cabeçalho: com um caso em cobrança, vão direto; com vários, você escolhe o credor e a carteira.',
          'Aba "Interações": os contatos mais recentes de todos os casos do devedor, do mais recente ao mais antigo.',
          'Cartão "Relacionados" com os registros ligados a este que o seu perfil pode abrir: casos, acordos, pagamentos, contestações, casos jurídicos e conversas.',
          'O envio do link do portal passou para o menu "Mais ações" (⋯); a lista de títulos filtrada pelo devedor fica em "Relacionados".',
        ],
      },
      { version: 5, items: ['Selo de vulnerabilidade no cabeçalho quando o devedor tem marcador ativo (a categoria só aparece para quem pode vê-la).'] },
      { version: 6, items: ['Para quem atende titulares, "Relacionados" mostra as solicitações LGPD do devedor; "Ver todas" abre a fila filtrada pelo titular.'] },
      {
        version: 7,
        items: [
          'Empresa em recuperação judicial ou falência: registre o processo (com motivo), convole em falência e encerre na própria ficha. Enquanto estiver em curso, a cobrança da empresa fica suspensa em todos os credores e o aviso leva ao caso jurídico.',
          'Atenção especial: para empresa, só "Outra situação"; superendividamento só aparece quando há dívida em carteira de consumo.',
        ],
      },
      { version: 8, items: ['Aba "Contatos": cartão "Melhor forma de contato" (para quem vê o score), com o canal preferido e os contatos pela taxa de atendimento ou resposta, a confiança e o melhor horário.'] },
      {
        version: 9,
        items: [
          'Empresas: nome fantasia, matriz ou filial e o atalho "Outras unidades da empresa" (mesma raiz do CNPJ).',
          'Contatos com a pessoa com quem se fala e o papel dela, por exemplo "A*** (Financeiro) · decide"; o nome completo aparece ao exibir o contato.',
          'Aba "Representantes" nas empresas: quem pode negociar pela empresa (sócio administrador, procurador), com validade e evidência.',
        ],
      },
      { version: 10, items: ['Contatos: o telefone, e-mail ou endereço recebido numa nova carga para um devedor que já existia mostra o selo "Novo" até a primeira tentativa (depois, "Novo · atendeu" ou "Novo · sem resposta" até o fim do prazo). A dica diz a data da carga e quando o credor confirmou o contato.'] },
    ],
  },
  {
    key: 'debt.titles',
    version: 2,
    path: '/titulos',
    tourId: 'debt.titles',
    changes: [{ version: 2, items: ['Vindo de um lote de importação ("Títulos importados"), a lista mostra só os títulos daquela carga; o filtro some no × ao lado.'] }],
  },
  // "correções de valor" antes do detalhe (":id" casaria o caminho)
  {
    key: 'debt.title-amounts',
    version: 4,
    path: '/titulos/correcoes-de-valor',
    tourId: 'debt.title-amounts',
    changes: [
      {
        version: 2,
        items: [
          'Aumentar o limite agora fica proposto até outra pessoa com permissão de aprovação confirmar na aba Limite; reduzir continua valendo na hora.',
          'Correção que aumenta algum componente, ou cuja soma das reduções passa do limite, vai para aprovação mesmo com o saldo final menor.',
        ],
      },
      {
        version: 3,
        items: [
          'A proposta de aumento do limite vence {{debt.proposalValidity}}: a aba Limite mostra a data; vencida, não pode mais ser confirmada e é descartada.',
          'O limite vale também por título: reduções aplicadas sem segunda pessoa no mesmo título {{debt.cumulativeWindow}} somam com a nova.',
        ],
      },
      {
        version: 4,
        items: [
          '"Para aprovar" virou uma lista com filtro de credor, saldo antes → depois e a diferença; "Revisar" abre a decisão.',
          'Na decisão: quanto o pedido passa do limite (valor e %), quem pediu × quem decide, "Aprovar e abrir a próxima" e o contador "1 de N" da mesma fila.',
        ],
      },
    ],
  },
  // decisão da fila antes do detalhe do título (":id" casaria o caminho)
  { key: 'debt.title-amount-approval', version: 1, path: '/titulos/correcoes-de-valor/:id', tourId: 'approval.decision' },
  {
    key: 'debt.title-detail',
    version: 6,
    path: '/titulos/:id',
    tourId: 'debt.title-detail',
    changes: [
      {
        version: 2,
        items: [
          'Corrigir valor: novo saldo por componente com motivo e evidência; acima do limite ou em aumento do saldo, outra pessoa aprova.',
          'Aba "Correções de valor" com o histórico do título e as cargas do credor diferentes do valor corrigido, para decidir qual vale.',
        ],
      },
      {
        version: 3,
        items: ['Corrigir valor: se algum componente aumenta, ou se a soma das reduções passa do limite, a correção vai para aprovação de outra pessoa mesmo com o saldo final menor.'],
      },
      {
        version: 4,
        items: ['Corrigir valor: reduções já aplicadas sem segunda pessoa no mesmo título {{debt.cumulativeWindow}} somam com a nova; passando do limite, a prévia avisa que vai para aprovação.'],
      },
      {
        version: 5,
        items: ['Suspender, transferir, devolver ao credor e cancelar ficam no menu "Mais ações" (⋯), com o cancelamento por último e separado; para voltar à lista, use a trilha no topo.'],
      },
      { version: 6, items: ['Cartão "Relacionados" com os registros ligados a este que o seu perfil pode abrir: devedor, caso da carteira, acordos que incluem o título, contestação, caso jurídico e o lote que trouxe o título.'] },
    ],
  },
  {
    key: 'collection.queue',
    version: 16,
    path: '/cobranca/fila',
    tourId: 'collection.station',
    changes: [
      { version: 2, items: ['"Posso contatar agora?": decisão do sistema por canal, com o próximo horário permitido.', 'Histórico mostra o nome de quem registrou cada interação.'] },
      { version: 3, items: ['Aba Negociação (Alt + 6): propostas em andamento e acordos do caso, com o cronograma.'] },
      { version: 4, items: ['Gerar Pix da próxima parcela do acordo (aba Negociação) ou de um título avulso (aba Títulos).'] },
      { version: 5, items: ['Ligar (Alt + L) com a decisão do porteiro por telefone antes de discar.', 'Barra de presença na telefonia (disponível, pausa com motivo) e tabulação obrigatória ao fim da chamada.'] },
      { version: 6, items: ['Tabulações com ação automática avisam o efeito antes do registro: contestação aberta (com o tipo), contato bloqueado por falecimento ou contato marcado como inválido.'] },
      {
        version: 7,
        items: [
          'Mensagem (Alt + W): contato ativo por WhatsApp, SMS ou e-mail a partir do caso, com template aprovado e a decisão do porteiro por contato antes do envio.',
          'Aba Conversas (Alt + 7): conversas do caso, abertas e encerradas, com quem atende e atalho para a caixa de entrada.',
        ],
      },
      {
        version: 8,
        items: [
          'O atendimento fica guardado por caso: abrir a ficha do devedor, o simulador ou outra tela e voltar não zera o cronômetro nem o que você digitou, e a aba ativa fica no endereço.',
          'Registro de interação no topo da coluna da direita, com o botão sempre à vista; "Posso contatar agora?" numa linha de chips (o detalhe só para os canais não liberados).',
          'Simulador e ficha do devedor abrem num painel ao lado, sem sair do caso. O acordo fecha ali mesmo: "Propor esta oferta", aceite com o canal do atendimento e formalização com a confirmação digitada, Pix e o cartão "Acordo formalizado".',
          'Abas que não cabem ficam em "Mais"; a tabela de títulos mostra sempre o saldo (o valor original fica no detalhe do saldo).',
        ],
      },
      {
        version: 9,
        items: ['O botão das abas recolhidas mostra a seta e quantas abas guarda ("Mais 3 ▾"); com uma delas aberta, mostra o nome curto dela e quantas outras há ("Agenda +2 ▾"); o nome completo aparece na dica.'],
      },
      {
        version: 10,
        items: ['Cada caso tem um endereço só: ao abrir um caso na estação, o endereço passa a ser o do caso, que você pode copiar e compartilhar; o menu continua marcando a estação.'],
      },
      { version: 11, items: ['Selo de vulnerabilidade no cabeçalho quando o devedor tem marcador ativo (a categoria só aparece para quem pode vê-la).'] },
      { version: 12, items: ['Histórico: a interação que você já avaliou mostra "Avaliado por você" com a nota no lugar de "Avaliar" (cada avaliador avalia um atendimento uma vez).'] }, { version: 13, items: ['Agendar retorno para outra pessoa: a lista mostra só quem pode trabalhar o caso.'] },
      { version: 14, items: ['Empresa em recuperação judicial ou falência: o caso fica inelegível e a ficha mostra desde quando, o processo e o atalho para o caso jurídico.'] }, { version: 15, items: ['Cartão "Melhor forma de contato" (para quem vê o score): canal preferido e os contatos do devedor pela taxa de atendimento ou resposta, com a explicação, a confiança e o melhor horário de cada um.'] },
      { version: 16, items: ['Contatos: o telefone, e-mail ou endereço recebido numa nova carga para um devedor que já existia mostra o selo "Novo" até a primeira tentativa (depois, "Novo · atendeu" ou "Novo · sem resposta" até o fim do prazo). A dica diz a data da carga e quando o credor confirmou o contato.'] },
    ],
  },
  {
    key: 'collection.case',
    version: 15,
    path: '/cobranca/casos/:id',
    tourId: 'collection.station',
    changes: [
      { version: 2, items: ['"Posso contatar agora?": decisão do sistema por canal, com o próximo horário permitido.', 'Histórico mostra o nome de quem registrou cada interação.'] },
      { version: 3, items: ['Aba Negociação (Alt + 6): propostas em andamento e acordos do caso, com o cronograma.'] },
      { version: 4, items: ['Gerar Pix da próxima parcela do acordo (aba Negociação) ou de um título avulso (aba Títulos).'] },
      { version: 5, items: ['Ligar (Alt + L) com a decisão do porteiro por telefone antes de discar.', 'Barra de presença na telefonia (disponível, pausa com motivo) e tabulação obrigatória ao fim da chamada.'] },
      { version: 6, items: ['Tabulações com ação automática avisam o efeito antes do registro: contestação aberta (com o tipo), contato bloqueado por falecimento ou contato marcado como inválido.'] },
      {
        version: 7,
        items: [
          'Mensagem (Alt + W): contato ativo por WhatsApp, SMS ou e-mail a partir do caso, com template aprovado e a decisão do porteiro por contato antes do envio.',
          'Aba Conversas (Alt + 7): conversas do caso, abertas e encerradas, com quem atende e atalho para a caixa de entrada.',
        ],
      },
      {
        version: 8,
        items: [
          'O atendimento fica guardado por caso: abrir a ficha do devedor, o simulador ou outra tela e voltar não zera o cronômetro nem o que você digitou, e a aba ativa fica no endereço.',
          'Registro de interação no topo da coluna da direita, com o botão sempre à vista; "Posso contatar agora?" numa linha de chips (o detalhe só para os canais não liberados).',
          'Simulador e ficha do devedor abrem num painel ao lado, sem sair do caso. O acordo fecha ali mesmo: "Propor esta oferta", aceite com o canal do atendimento e formalização com a confirmação digitada, Pix e o cartão "Acordo formalizado".',
          'Abas que não cabem ficam em "Mais"; a tabela de títulos mostra sempre o saldo (o valor original fica no detalhe do saldo).',
        ],
      },
      {
        version: 9,
        items: ['O botão das abas recolhidas mostra a seta e quantas abas guarda ("Mais 3 ▾"); com uma delas aberta, mostra o nome curto dela e quantas outras há ("Agenda +2 ▾"); o nome completo aparece na dica.'],
      },
      {
        version: 10,
        items: [
          'Endereço único do caso: quem opera abre o caso na estação; quem só consulta (jurídico, gestor, auditoria) vê a ficha do caso sem fila, cronômetro nem registro de interação, com as ações do seu perfil em "Mais ações".',
          'Histórico: "Avaliar" em cada atendimento de outra pessoa abre a monitoria já preenchida (para quem avalia a qualidade).',
        ],
      },
      { version: 11, items: ['Selo de vulnerabilidade no cabeçalho quando o devedor tem marcador ativo (a categoria só aparece para quem pode vê-la).'] },
      { version: 13, items: ['Empresa em recuperação judicial ou falência: o caso fica inelegível e a ficha mostra desde quando, o processo e o atalho para o caso jurídico.'] },
      { version: 14, items: ['Cartão "Melhor forma de contato" (para quem vê o score): canal preferido e os contatos do devedor pela taxa de atendimento ou resposta, com a explicação, a confiança e o melhor horário de cada um.'] },
      { version: 15, items: ['Contatos: o telefone, e-mail ou endereço recebido numa nova carga para um devedor que já existia mostra o selo "Novo" até a primeira tentativa (depois, "Novo · atendeu" ou "Novo · sem resposta" até o fim do prazo). A dica diz a data da carga e quando o credor confirmou o contato.'] },
    ],
  },
  {
    key: 'collection.cases',
    version: 4,
    path: '/cobranca/casos',
    tourId: 'collection.cases',
    changes: [
      { version: 2, items: ['Filtro "Devedor (nome ou CPF/CNPJ)": o CPF ou CNPJ precisa estar completo.', 'Clique no título das colunas com seta para ordenar. Saldo, atraso e próxima ação.', 'Filtros, página e ordenação ficam no endereço: ao abrir um registro e voltar, a lista volta como estava.'] },
      { version: 3, items: ['Filtro por carteira. Os números do painel operacional abrem esta lista já filtrada.', 'No celular, os filtros ficam no botão "Filtros (n)", com quantos estão aplicados.'] }, { version: 4, items: ['Atribuir caso: a lista mostra só operadores ativos que trabalham casos e têm acesso ao credor do caso.'] }],
  },
  { key: 'collection.agenda', version: 1, path: '/cobranca/agenda', tourId: 'collection.agenda' },
  {
    key: 'collection.queues',
    version: 5,
    path: '/cobranca/filas',
    tourId: 'collection.queues',
    changes: [
      { version: 2, items: ['O nome da fila abre a lista de casos dessa fila.'] },
      { version: 3, items: ['Critério "Só devedores com marcador de vulnerabilidade": crie uma fila de atendimento cuidadoso para esses casos.'] }, { version: 4, items: ['Redistribuir casos: "de" mostra quem tem casos ativos (inclusive quem saiu, marcado como inativo); "para" mostra só quem pode receber.'] },
      {
        version: 5,
        items: [
          'Critério "Só devedores com contato novo": uma fila para trabalhar primeiro os contatos recebidos numa nova carga; o caso sai dela na primeira tentativa pelo contato.',
          'Critério "Tipo de pessoa": filas só de pessoa física ou só de pessoa jurídica. A coluna Critérios resume também esses filtros.',
        ],
      },
    ],
  },
  {
    key: 'collection.tabulations',
    version: 2,
    path: '/cobranca/tabulacoes',
    changes: [{ version: 2, items: ['Ação automática por tabulação: abrir contestação, registrar falecimento (bloqueia o contato) ou invalidar o contato usado.'] }],
  },
  { key: 'collection.settings', version: 1, path: '/cobranca/parametros' },
  {
    key: 'collection.disputes',
    version: 5,
    path: '/cobranca/contestacoes',
    tourId: 'collection.disputes',
    changes: [
      { version: 2, items: ['Detalhe da contestação com número, vencimento e valor dos títulos e quem anexou cada evidência.'] },
      {
        version: 3,
        items: [
          'Responsável por contestação: "Pegar para mim", filtro "Minhas" e distribuição automática ao analista menos carregado.',
          'Prazo interno da equipe ao lado do prazo de resposta ao devedor, com regras por tipo e por credor.',
          'Página da contestação com pedido formal ao credor, resposta do credor, resposta ao devedor e ajuste sugerido no título.',
        ],
      },
      { version: 4, items: ['Filtro "Resposta por e-mail a confirmar" e pendência das respostas do credor recebidas por e-mail.'] },
      {
        version: 5,
        items: [
          'Contestação feita pelo devedor no portal entra sozinha na fila, com o prazo de resposta contando do pedido no portal.',
          'No celular, os filtros ficam no botão "Filtros".',
        ],
      },
    ],
  },
  {
    key: 'collection.dispute-config',
    version: 3,
    path: '/cobranca/contestacoes/configuracao',
    tourId: 'collection.dispute-config',
    changes: [{ version: 2, items: ['Aba "E-mails em quarentena": respostas do credor por e-mail que falharam numa verificação, para liberar ao analista ou descartar com motivo.'] }, { version: 3, items: ['Grupo de analistas e redistribuição: as listas mostram só quem pode atuar nas contestações do credor; o credor vem antes do analista.'] }],
  },
  {
    key: 'collection.dispute',
    version: 3,
    path: '/cobranca/contestacoes/:id',
    tourId: 'collection.dispute',
    changes: [
      {
        version: 2,
        items: [
          'Respostas do credor por e-mail no canal com o credor, "a confirmar": texto sem formatação, anexos verificados como evidências do credor e o original para conferência.',
          'Confirme a posição do credor a partir do e-mail; só então o pedido deixa de aguardar o credor.',
          'Documentos enviados pelo credor pela integração mostram quantos estão em verificação de segurança ou foram recusados.',
          'Contestação procedente com valor a corrigir abre a correção no título já vinculada.',
        ],
      },
      {
        version: 3,
        items: [
          'Canal com o credor organizado por pedido, como uma conversa: o "E-mail enviado ao credor" (para, cópia, prazo, envios) e, logo abaixo, os "E-mails recebidos do credor" e as respostas registradas.',
          'Mensagens abertas num quadro com rolagem; no e-mail recebido, as verificações em selos, o motivo da quarentena em destaque e as ações numa linha própria.',
        ],
      },
    ],
  },
  {
    key: 'negotiation.simulator',
    version: 4,
    path: '/negociacao/simulador',
    tourId: 'negotiation.simulator',
    changes: [
      { version: 2, items: ['Condições validadas viram proposta: "Propor" dentro da alçada ou "Pedir aprovação" acima dela.', 'Renegociação de acordo a partir da ficha do acordo.'] },
      { version: 3, items: ['Ícone "i" ao lado de cada indicador: o que é o número, como é calculado e como usar na decisão, ao passar o mouse, tocar ou com Tab.', 'Tour desta tela explica a dívida atualizada, as ofertas e a sua alçada.'] },
      {
        version: 4,
        items: [
          'Aberto pelo caso, simula sozinho. Cada oferta tem "Propor esta oferta": a validação roda por trás e a confirmação abre direto.',
          'Forma de pagamento como na oferta ("Entrada + 11 parcelas"), sem contar a entrada no número de parcelas; descontos por componente em "avançado".',
        ],
      },
    ],
  },
  {
    key: 'negotiation.proposals',
    version: 3,
    path: '/negociacao/propostas',
    tourId: 'negotiation.proposals',
    changes: [
      { version: 2, items: ['Filtro "Devedor (nome ou CPF/CNPJ)": o CPF ou CNPJ precisa estar completo.', 'Clique no título das colunas com seta para ordenar. Criação e total.', 'Filtros, página e ordenação ficam no endereço: ao abrir um registro e voltar, a lista volta como estava.'] },
      { version: 3, items: ['No celular, os filtros ficam no botão "Filtros (n)", com quantos estão aplicados.'] },
    ],
  },
  {
    key: 'negotiation.proposal-detail',
    version: 3,
    path: '/negociacao/propostas/:id',
    tourId: 'negotiation.proposal-detail',
    changes: [
      { version: 2, items: ['Uma ação principal em azul (aprovar, registrar aceite ou formalizar); as demais em cinza ao lado.'] },
      { version: 3, items: ['Aguardando a sua aprovação: quanto passa da alçada de quem negociou (valor e %), a sua alçada, o desconto em % da dívida e o nome das alçadas, sem códigos.'] },
    ],
  },
  {
    key: 'negotiation.approvals',
    version: 2,
    path: '/negociacao/aprovacoes',
    tourId: 'negotiation.approvals',
    changes: [{ version: 2, items: ['Filtro de credor e colunas de desconto (% da dívida), prazo ("vence hoje") e alçada de quem negociou.', '"Revisar" abre a decisão: "Aprovar e abrir a próxima", o contador "1 de N" e a volta para a fila com o filtro.'] }],
  },
  { key: 'negotiation.approval', version: 1, path: '/negociacao/aprovacoes/:id', tourId: 'approval.decision' },
  {
    key: 'negotiation.agreements',
    version: 2,
    path: '/negociacao/acordos',
    changes: [{ version: 2, items: ['Filtro "Devedor (nome ou CPF/CNPJ)": o CPF ou CNPJ precisa estar completo.', 'Clique no título das colunas com seta para ordenar. Formalização e total.', 'Filtros, página e ordenação ficam no endereço: ao abrir um registro e voltar, a lista volta como estava.'] }],
  },
  {
    key: 'negotiation.agreement-detail',
    version: 5,
    path: '/negociacao/acordos/:id',
    tourId: 'negotiation.agreement-detail',
    changes: [
      { version: 2, items: ['Gerar Pix por parcela (QR Code e copia e cola) e informar pagamento recebido fora do Pix.'] },
      { version: 3, items: ['Ao informar pagamento, o aviso de que o valor vai para a aprovação de outra pessoa aparece para todos que informam, mesmo sem acesso aos parâmetros de pagamentos.'] },
      { version: 4, items: ['Cancelar acordo passou para o menu "Mais ações" (⋯) no cabeçalho da ficha.'] },
      { version: 5, items: ['Cada pagamento aplicado abre o pagamento (data na tabela) e o nome do devedor abre a ficha dele.', 'Cartão "Relacionados" com os registros ligados a este que o seu perfil pode abrir: devedor, caso, proposta, pagamentos e acordos da renegociação.'] },
    ],
  },
  {
    key: 'payment.list',
    version: 2,
    path: '/pagamentos',
    tourId: 'payment.list',
    changes: [{ version: 2, items: ['Ao informar pagamento, o aviso de que o valor vai para a aprovação de outra pessoa aparece para todos que informam, mesmo sem acesso aos parâmetros de pagamentos.'] }],
  },
  { key: 'payment.instructions', version: 1, path: '/pagamentos/instrucoes', tourId: 'payment.instructions' },
  { key: 'payment.instruction', version: 1, path: '/pagamentos/instrucoes/:id', tourId: 'payment.instruction' },
  {
    key: 'payment.review',
    version: 4,
    path: '/pagamentos/aprovacoes',
    tourId: 'payment.review',
    changes: [
      { version: 2, items: ['Coluna "Destino solicitado": para onde o valor vai (parcelas do acordo ou títulos) antes de aprovar.'] },
      { version: 3, items: ['Filtros de credor e de onde o pagamento entrou; "Revisar" abre a decisão com quanto passa do limite, "Aprovar e abrir a próxima" e o contador "1 de N".'] },
      { version: 4, items: ['No celular, os filtros ficam no botão "Filtros (n)", com quantos estão aplicados.'] },
    ],
  },
  // decisão da fila antes do detalhe do pagamento (":id" casaria o caminho)
  { key: 'payment.approval', version: 1, path: '/pagamentos/aprovacoes/:id', tourId: 'approval.decision' },
  { key: 'payment.reallocation', version: 1, path: '/pagamentos/sobras', tourId: 'payment.reallocation' },
  { key: 'payment.reconciliation', version: 1, path: '/pagamentos/conciliacao', tourId: 'payment.reconciliation' },
  {
    key: 'payment.statement',
    version: 5,
    path: '/pagamentos/conciliacao/:id',
    tourId: 'payment.statement',
    changes: [
      { version: 2, items: ['Ícone "i" ao lado de cada indicador: o que é o número, como é calculado e como usar na decisão, ao passar o mouse, tocar ou com Tab.'] },
      { version: 3, items: ['Botão "Tentar novamente" na importação com falha: continua de onde parou, sem baixa repetida.', 'A falha mostra a linha do arquivo onde o problema apareceu.'] },
      { version: 4, items: ['Botão "Cancelar processamento" na importação na fila ou em processamento: para onde está, com motivo, e o que já foi gravado fica valendo.', 'A importação cancelada aceita "Tentar novamente", que continua de onde parou.'] },
      { version: 5, items: ['"Cancelar processamento" foi para o menu "Mais ações" (⋯), separado das demais ações.'] },
    ],
  },
  {
    key: 'payment.divergences',
    version: 2,
    path: '/pagamentos/divergencias',
    tourId: 'payment.divergences',
    changes: [{ version: 2, items: ['Filtros por credor e por data de abertura ("Aberta de" e "Aberta até"). O painel de confiabilidade abre esta lista já filtrada.'] }],
  },
  {
    key: 'payment.pix-accounts',
    version: 2,
    path: '/pagamentos/contas-pix',
    tourId: 'payment.pix-accounts',
    changes: [{ version: 2, items: ['Webhook em HMAC por padrão; o mTLS aparece desabilitado, com a explicação, enquanto o ambiente não verificar o certificado do PSP.'] }],
  },
  { key: 'payment.settings', version: 1, path: '/pagamentos/parametros' },
  {
    key: 'publicapi.tokens',
    version: 2,
    path: '/integracoes/tokens',
    tourId: 'publicapi.tokens',
    changes: [{ version: 2, items: ['Nova situação "Pausado pela plataforma": as chamadas do token são recusadas, sem cobrança, até a plataforma retomar.'] }],
  },
  {
    key: 'publicapi.token',
    version: 4,
    path: '/integracoes/tokens/:id',
    tourId: 'publicapi.token',
    changes: [{ version: 2, items: ['Aviso de pausa pela plataforma, com motivo e retomada agendada; você continua podendo editar, rotacionar e revogar.', 'Revogação feita pela plataforma identificada na ficha.'] }, { version: 3, items: ['Editar e Rotacionar só aparecem se o seu perfil tem as permissões de todos os escopos do token (as mesmas exigidas para criá-lo); Revogar continua disponível.'] }, { version: 4, items: ['"Revogar token" foi para o menu "Mais ações" (⋯), separado das demais ações; continua imediato, com motivo.'] }],
  },
  {
    key: 'publicapi.usage',
    version: 2,
    path: '/integracoes/consumo',
    tourId: 'publicapi.usage',
    changes: [{ version: 2, items: ['Ícone "i" ao lado de cada indicador: o que é o número, como é calculado e como usar na decisão, ao passar o mouse, tocar ou com Tab.'] }],
  },
  {
    key: 'settlement.position',
    version: 2,
    path: '/posicao-credor',
    tourId: 'settlement.position',
    changes: [{ version: 2, items: ['Ícone "i" ao lado de cada indicador: o que é o número, como é calculado e como usar na decisão, ao passar o mouse, tocar ou com Tab.'] }],
  },
  { key: 'publicapi.billing', version: 2, path: '/integracoes/extratos', tourId: 'publicapi.billing' },
  {
    key: 'payment.detail',
    version: 4,
    path: '/pagamentos/:id',
    tourId: 'payment.detail',
    changes: [
      { version: 2, items: ['Pagamento aguardando aprovação mostra o destino solicitado por quem informou.'] },
      { version: 3, items: ['Estornar passou para o menu "Mais ações" (⋯) no cabeçalho da ficha.'] },
      { version: 4, items: ['O nome do devedor abre a ficha dele.', 'Cartão "Relacionados" com os registros ligados a este que o seu perfil pode abrir: devedor, acordos e títulos que receberam o pagamento.'] },
    ],
  },
  { key: 'settlement.rules', version: 1, path: '/prestacao-de-contas/regras', tourId: 'settlement.rules' },
  {
    key: 'settlement.rule-set',
    version: 2,
    path: '/prestacao-de-contas/regras/:id',
    tourId: 'settlement.rule-set',
    changes: [{ version: 2, items: ['"Descartar rascunho" foi para o menu "Mais ações" (⋯); "Descartar alterações" (o que não foi salvo) continua no cabeçalho.'] }],
  },
  {
    key: 'settlement.ledger',
    version: 2,
    path: '/prestacao-de-contas/razao',
    tourId: 'settlement.ledger',
    changes: [{ version: 2, items: ['Filtros por carteira e por lançamento (recebimentos, estornos ou os dois). Os painéis de recuperação e operacional abrem esta lista já filtrada.'] }],
  },
  { key: 'settlement.statements', version: 1, path: '/prestacao-de-contas/demonstrativos', tourId: 'settlement.statements' },
  {
    key: 'settlement.statement',
    version: 4,
    path: '/prestacao-de-contas/demonstrativos/:id',
    tourId: 'settlement.statement',
    changes: [
      { version: 2, items: ['Ícone "i" ao lado de cada indicador: o que é o número, como é calculado e como usar na decisão, ao passar o mouse, tocar ou com Tab.'] },
      { version: 3, items: ['Cancelar demonstrativo passou para o menu "Mais ações" (⋯) no cabeçalho da ficha.'] },
      { version: 4, items: ['Na aba de recebimentos, a data de cada recebimento abre o pagamento de origem.'] },
    ],
  },
  { key: 'settlement.transfers', version: 1, path: '/prestacao-de-contas/repasses', tourId: 'settlement.transfers' },
  {
    key: 'incentive.performance',
    version: 4,
    path: '/remuneracao/desempenho',
    tourId: 'incentive.performance',
    changes: [
      { version: 2, items: ['Ícone "i" ao lado de cada indicador: o que é o número, como é calculado e como usar na decisão, ao passar o mouse, tocar ou com Tab.', 'Tour desta tela refeito: cada passo mostra um indicador e o que fazer quando ele sobe ou cai.'] },
      { version: 3, items: ['Para o negociador, "Meu desempenho" reúne os atalhos para a produtividade, os extratos e lançamentos de comissão, a monitoria e as ocorrências, que saíram do menu.'] },
      { version: 4, items: ['Agrupado por negociador, a nota de monitoria abre as avaliações da pessoa no mês, e "Avaliar chamadas" abre as chamadas atendidas por ela no mesmo período.'] },
    ],
  },
  { key: 'incentive.statements', version: 1, path: '/remuneracao/extratos', tourId: 'incentive.statements' },
  { key: 'incentive.preview', version: 1, path: '/remuneracao/extratos/previa' },
  { key: 'incentive.statement', version: 1, path: '/remuneracao/extratos/:id', tourId: 'incentive.statement' },
  { key: 'incentive.ledger', version: 1, path: '/remuneracao/lancamentos' },
  { key: 'incentive.plans', version: 1, path: '/remuneracao/planos', tourId: 'incentive.plans' },
  {
    key: 'incentive.plan',
    version: 2,
    path: '/remuneracao/planos/:id',
    tourId: 'incentive.plan',
    changes: [{ version: 2, items: ['"Descartar rascunho" e "Aposentar versão" foram para o menu "Mais ações" (⋯), separados das demais ações; a confirmação continua.'] }],
  },
  { key: 'incentive.plan-rules', version: 2, path: '/remuneracao/planos/:id/regras', tourId: 'incentive.plan-rules', changes: [{ version: 2, items: ['Botão "Ficha do plano" no cabeçalho para voltar ao plano.'] }] },
  {
    key: 'incentive.goals',
    version: 3,
    path: '/remuneracao/metas',
    changes: [{ version: 2, items: ['A meta agora tem unidade: escolha "Valor (R$)", digitado com centavos pela direita, ou "Percentual (%)". Quando o plano só aceita uma, ela já vem marcada.'] }, { version: 3, items: ['Meta por negociador: a lista mostra só negociadores no seu escopo.'] }],
  },
  { key: 'incentive.periods', version: 1, path: '/remuneracao/periodos' },
  { key: 'incentive.exports', version: 1, path: '/remuneracao/exportacao' },
  {
    key: 'incentive.evaluations',
    version: 5,
    path: '/remuneracao/qualidade/avaliacoes',
    changes: [
      { version: 2, items: ['Avalie a partir do atendimento: "Avaliar" em Chamadas e gravações, na conversa e no histórico do caso abre o formulário com o negociador, a referência e a data preenchidos.', 'Cada avaliação tem o link "Ver" de volta para o caso, a conversa, o acordo ou a chamada avaliada.'] },
      {
        version: 3,
        items: [
          'Formulário de monitoria refeito: o atendimento avaliado aparece num cartão, cada critério tem uma escala de 0 a 10 de um clique e a nota ponderada fica sempre visível no rodapé.',
          'Sem atendimento de origem, a monitoria é uma avaliação geral (sem identificador para digitar), com o caminho para avaliar no próprio atendimento.',
        ],
      },
      { version: 4, items: ['Cada avaliador avalia um atendimento uma vez: o que você já avaliou mostra "Avaliado por você" com a nota no lugar de "Avaliar"; outro avaliador ainda pode avaliá-lo.', 'A avaliação feita no histórico do caso aponta para a interação avaliada, e "Ver interação" volta ao histórico do caso.'] }, { version: 5, items: ['Avaliação geral: a lista mostra só negociadores que você pode avaliar (sem você), só pelo nome.'] }],
  },
  { key: 'incentive.occurrences', version: 2, path: '/remuneracao/qualidade/ocorrencias', changes: [{ version: 2, items: ['Registrar ocorrência: a lista mostra só negociadores que você pode avaliar (sem você).'] }] },
  { key: 'incentive.quality-forms', version: 1, path: '/remuneracao/qualidade/formularios' },
  { key: 'incentive.attribution', version: 1, path: '/remuneracao/atribuicoes/:id' },
  { key: 'telephony.campaigns', version: 1, path: '/telefonia/campanhas', tourId: 'telephony.campaigns' },
  {
    key: 'telephony.campaign',
    version: 2,
    path: '/telefonia/campanhas/:id',
    tourId: 'telephony.campaign',
    changes: [{ version: 2, items: ['Ícone "i" ao lado de cada indicador: o que é o número, como é calculado e como usar na decisão, ao passar o mouse, tocar ou com Tab.'] }],
  },
  {
    key: 'telephony.calls',
    version: 5,
    path: '/telefonia/chamadas',
    tourId: 'telephony.calls',
    changes: [
      { version: 2, items: ['"Avaliar este atendimento" na linha da chamada encerrada (para quem avalia a qualidade): a monitoria abre já preenchida.'] },
      { version: 3, items: ['Ouça e avalie no mesmo passo: "Avaliar este atendimento" também fica no player da gravação, e a monitoria abre por cima com o áudio tocando.', 'Filtro por operador, para monitorar o atendimento de uma pessoa.'] },
      { version: 4, items: ['Chamada que você já avaliou mostra o ícone de monitoria preenchido, com a nota na dica, no lugar de "Avaliar"; outro avaliador ainda pode avaliá-la.'] },
      { version: 5, items: ['Filtro por devedor (para quem consulta devedores): a resposta ao titular abre as chamadas dele direto.'] },
    ],
  },
  {
    key: 'telephony.agents',
    version: 3,
    path: '/telefonia/operadores',
    changes: [{ version: 2, items: ['Com mais de uma plataforma de telefonia, "Ramais por plataforma" define o ramal do operador em cada conta ou o marca como fora dela.'] }, { version: 3, items: ['Habilitar operador: a lista mostra só quem está ativo e tem permissão para usar a telefonia.'] }],
  },
  {
    key: 'telephony.accounts',
    version: 2,
    path: '/telefonia/plataformas',
    tourId: 'telephony.accounts',
    changes: [{ version: 2, items: ['Ícone "i" ao lado de cada indicador: o que é o número, como é calculado e como usar na decisão, ao passar o mouse, tocar ou com Tab.', 'Tour desta tela com a comparação entre plataformas: atendimento, falhas, tempos e custo estimado.'] }],
  },
  { key: 'assistant.policies', version: 1, path: '/assistente/politicas', tourId: 'assistant.policies' },
  {
    key: 'assistant.policy',
    version: 3,
    path: '/assistente/politicas/:id',
    tourId: 'assistant.policy',
    changes: [
      { version: 2, items: ['O perfil de alçada é escolhido por quem gerencia alçadas; sem essa permissão, a tela só informa o perfil e "Publicar" não aparece na versão com perfil próprio.'] },
      { version: 3, items: ['"Aposentar versão" foi para o menu "Mais ações" (⋯), separado das demais ações.'] },
    ],
  },
  { key: 'assistant.reviews', version: 1, path: '/assistente/revisao', tourId: 'assistant.reviews' },
  { key: 'assistant.turn', version: 1, path: '/assistente/turnos/:id', tourId: 'assistant.turn' },
  {
    key: 'assistant.quality',
    version: 3,
    path: '/assistente/qualidade',
    tourId: 'assistant.quality',
    changes: [
      { version: 2, items: ['Ícone "i" ao lado de cada indicador: o que é o número, como é calculado e como usar na decisão, ao passar o mouse, tocar ou com Tab.'] },
      { version: 3, items: ['Quebra precoce na mesma regra dos painéis: a taxa considera só os acordos formalizados há pelo menos a janela; os recentes aparecem à parte, em maturação.'] },
    ],
  },
  {
    key: 'communication.inbox',
    version: 6,
    path: '/conversas',
    tourId: 'communication.inbox',
    changes: [
      {
        version: 2,
        items: ['Sem conversas suas aguardando, a caixa abre na Fila; a aba escolhida fica no endereço (voltar do caso reabre a mesma aba) e "Minhas" vazia aponta para a Fila.'],
      },
      { version: 3, items: ['"Avaliar atendimento" no cabeçalho da conversa atendida por outra pessoa (para quem avalia a qualidade): a monitoria abre já preenchida.'] },
      { version: 4, items: ['Conversa que você já avaliou mostra "Avaliado por você" com a nota no lugar de "Avaliar atendimento".'] },
      { version: 5, items: ['Ao escolher o template, o primeiro nome do devedor da conversa já vem preenchido e a prévia aparece pronta.', 'A mensagem com o código de acesso ao portal aparece como "Código de acesso ao portal enviado (oculto por segurança)": o código não fica legível na conversa.'] }, { version: 6, items: ['Atribuir conversa: a lista mostra só quem pode ver e responder na caixa de entrada e, com caso, trabalhar o caso; conversa sem caso também pode ser atribuída.'] }],
  },
  {
    key: 'communication.templates',
    version: 2,
    path: '/conversas/templates',
    tourId: 'communication.templates',
    changes: [{ version: 2, items: ['Finalidade "Autenticação (código de acesso)": o template do código de acesso ao portal tem corpo curto, só o código e a validade; no WhatsApp, categoria de autenticação da Meta. O código só sai com template dessa finalidade.'] }],
  },
  { key: 'communication.channels', version: 1, path: '/conversas/canais' },
  {
    key: 'communication.conversation',
    version: 4,
    path: '/conversas/:id',
    tourId: 'communication.inbox',
    changes: [
      { version: 2, items: ['"Avaliar atendimento" no cabeçalho da conversa atendida por outra pessoa (para quem avalia a qualidade): a monitoria abre já preenchida.'] },
      { version: 3, items: ['Conversa que você já avaliou mostra "Avaliado por você" com a nota no lugar de "Avaliar atendimento".'] },
      { version: 4, items: ['Ao escolher o template, o primeiro nome do devedor da conversa já vem preenchido e a prévia aparece pronta.', 'A mensagem com o código de acesso ao portal aparece como "Código de acesso ao portal enviado (oculto por segurança)": o código não fica legível na conversa.'] },
    ],
  },
  { key: 'campaign.list', version: 1, path: '/campanhas', tourId: 'campaign.list' },
  { key: 'campaign.segments', version: 1, path: '/campanhas/segmentos' },
  { key: 'campaign.segment-new', version: 2, path: '/campanhas/segmentos/novo', tourId: 'campaign.segment', changes: [{ version: 2, items: ['Filtros "Contato novo" (com ou sem contato recebido numa nova carga ainda sem tentativa) e "Tipo de pessoa" (física ou jurídica).'] }] },
  { key: 'campaign.segment', version: 2, path: '/campanhas/segmentos/:id', tourId: 'campaign.segment', changes: [{ version: 2, items: ['Filtros "Contato novo" (com ou sem contato recebido numa nova carga ainda sem tentativa) e "Tipo de pessoa" (física ou jurídica).'] }] },
  { key: 'campaign.experiments', version: 1, path: '/campanhas/testes' },
  { key: 'campaign.detail', version: 1, path: '/campanhas/:id', tourId: 'campaign.detail' },
  {
    key: 'campaign.cadence',
    version: 2,
    path: '/campanhas/:id/regua',
    tourId: 'campaign.cadence',
    changes: [{ version: 2, items: ['Novas origens de variável: "Nome fantasia da empresa" (nome_fantasia) e "Saudação pelo tipo de pessoa" (saudacao), que usa o primeiro nome da pessoa e o nome da empresa.'] }],
  },
  {
    key: 'campaign.execution',
    version: 2,
    path: '/campanhas/:id/execucao',
    tourId: 'campaign.execution',
    changes: [{ version: 2, items: ['Ícone "i" ao lado de cada indicador: o que é o número, como é calculado e como usar na decisão, ao passar o mouse, tocar ou com Tab.'] }],
  },
  {
    key: 'campaign.experiment',
    version: 2,
    path: '/campanhas/:id/testes',
    tourId: 'campaign.experiment',
    changes: [{ version: 2, items: ['Ícone "i" ao lado de cada indicador: o que é o número, como é calculado e como usar na decisão, ao passar o mouse, tocar ou com Tab.'] }],
  },
  {
    key: 'legal.dashboard',
    version: 3,
    path: '/juridico',
    tourId: 'legal.dashboard',
    changes: [
      { version: 2, items: ['Ícone "i" ao lado de cada indicador: o que é o número, como é calculado e como usar na decisão, ao passar o mouse, tocar ou com Tab.'] },
      { version: 3, items: ['"Valor recuperado" passou a se chamar "Valor liberado judicialmente": é a soma dos valores liberados pelo juízo. Recuperado é só pagamento confirmado.'] },
    ],
  },
  { key: 'legal.matters', version: 1, path: '/juridico/casos' },
  { key: 'legal.matter-detail', version: 3, path: '/juridico/casos/:id', tourId: 'legal.matter-detail', changes: [{ version: 2, items: ['Abas sem ícones e valores do caso em cartões; "Custas e despesas" fica em vermelho quando passa do orçamento.'] }, { version: 3, items: ['Responsável por prazo: o servidor aplica a mesma regra da lista (acesso ao jurídico e ao credor; escritório parceiro só entre colegas).'] }] },
  { key: 'legal.rules', version: 1, path: '/juridico/regras' },
  { key: 'legal.firms', version: 2, path: '/juridico/escritorios', changes: [{ version: 2, items: ['Vincular advogado: só quem tem acesso ao jurídico e ainda não pertence a outro escritório.'] }] },
  { key: 'legal.settings', version: 1, path: '/juridico/parametros' },
  {
    key: 'negotiation.policies',
    version: 2,
    path: '/negociacao/politicas',
    tourId: 'negotiation.policies',
    changes: [{ version: 2, items: ['"Saúde das políticas" no cabeçalho: resultados da política por carteira e faixa de atraso, com sugestões de revisão.'] }],
  },
  { key: 'negotiation.policy-health', version: 1, path: '/negociacao/politicas/saude' },
  {
    key: 'negotiation.policy-detail',
    version: 2,
    path: '/negociacao/politicas/:id',
    tourId: 'negotiation.policy-detail',
    changes: [
      {
        version: 2,
        items: [
          'Cada sobreposição tem um nome (ex.: "Atraso longo"), mostrado na aba; o código, que vai para a memória de cálculo, é gerado a partir do nome se ficar vazio.',
          'Campos das regras alinhados em colunas iguais (o parcelamento não fica mais desencontrado).',
        ],
      },
    ],
  },
  { key: 'negotiation.indexes', version: 1, path: '/negociacao/indices' },
  { key: 'negotiation.authority', version: 2, path: '/negociacao/alcadas', tourId: 'negotiation.authority', changes: [{ version: 2, items: ['Atribuir alçada: a lista mostra só usuários habilitados com acesso ao credor escolhido (sem você); trocar o credor desmarca quem não o acessa.'] }] },
  { key: 'portal.configs', version: 1, path: '/autonegociacao' },
  {
    key: 'portal.requests',
    version: 3,
    path: '/autonegociacao/pedidos',
    changes: [
      { version: 2, items: ['A mensagem escrita pelo devedor não aparece mais na lista: abra em "Ver mensagem do devedor". Cada consulta fica registrada no log de acesso.'] },
      { version: 3, items: ['"Abrir conversa" leva direto à conversa do pedido na caixa de entrada, pronta para responder.'] },
    ],
  },
  {
    key: 'portal.config',
    version: 3,
    path: '/autonegociacao/:id',
    changes: [
      { version: 2, items: ['A prévia da marca deixa claro que é só a prévia das cores (não clicável); para navegar no portal, use "Abrir portal".'] },
      { version: 3, items: ['Com "Data de nascimento", empresas (CNPJ) confirmam o acesso com os 4 últimos números do contrato; no aceite, informam quem está negociando e, sem representante confirmado, um atendente conclui o acordo.'] },
    ],
  },
  {
    key: 'ingestion.batches',
    version: 3,
    path: '/importacao',
    tourId: 'ingestion.batches',
    changes: [
      {
        version: 2,
        items: [
          '"Nova importação" abre a conferência em passos: formato dos valores, validação, totais da planilha, alertas e resumo.',
          'Conferências em andamento e aguardando aprovação aparecem no topo da lista.',
        ],
      },
      {
        version: 3,
        items: ['"Importação PDF inteligente": quando o credor só envia PDF, o sistema encontra os títulos e você valida o mapa antes de importar.'],
      },
    ],
  },
  { key: 'ingestion.layouts', version: 1, path: '/importacao/layouts' },
  { key: 'ingestion.review-start', version: 1, path: '/importacao/nova', tourId: 'ingestion.review-start' },
  {
    key: 'ingestion.pdf-start',
    version: 2,
    path: '/importacao/pdf/nova',
    tourId: 'ingestion.pdf-start',
    changes: [{ version: 2, items: ['Escolha primeiro o credor: a lista de carteiras mostra só as dele.'] }],
  },
  {
    key: 'ingestion.pdf-import',
    version: 4,
    path: '/importacao/pdf/:id',
    tourId: 'ingestion.pdf-import',
    changes: [
      {
        version: 2,
        items: [
          'Filtros por situação (com contadores), devedor e texto; seleção da página ou de todos os títulos do filtro.',
          'Excluir do lote e reincluir em massa, com motivo único; títulos de confiança baixa continuam confirmados um a um.',
        ],
      },
      { version: 3, items: ['"Descartar importação" foi para o menu "Mais ações" (⋯), separado das demais ações; o motivo continua obrigatório.'] },
      { version: 4, items: ['Com o mapa salvo do credor em uso, "Salvar mapa" não aparece (não há o que salvar); ajuste o mapa para salvar uma versão nova, que agora substitui a anterior sem erro.'] },
    ],
  },
  {
    key: 'ingestion.review',
    version: 3,
    path: '/importacao/conferencias/:id',
    tourId: 'ingestion.review',
    changes: [
      { version: 2, items: ['Ícone "i" ao lado de cada indicador: o que é o número, como é calculado e como usar na decisão, ao passar o mouse, tocar ou com Tab.'] },
      { version: 3, items: ['"Cancelar conferência" foi para o menu "Mais ações" (⋯).'] },
    ],
  },
  {
    key: 'ingestion.batch-detail',
    version: 5,
    path: '/importacao/:id',
    tourId: 'ingestion.batch-detail',
    changes: [
      { version: 2, items: ['Importar a prévia ou reprocessar o arquivo passa pela conferência; a leitura aplicada aos valores aparece no recebimento.'] },
      { version: 3, items: ['Com a retenção de arquivos de importação ligada, o lote mostra quando o arquivo original foi descartado; sem ele, não há download nem reprocessamento.'] },
      { version: 4, items: ['"Títulos importados" abre a lista de títulos desta carga (os criados ou atualizados por ela).'] },
      { version: 5, items: ['Carga concluída mostra quantos devedores que já existiam receberam contato novo; "Ver os devedores" abre a lista filtrada por esta carga.'] },
    ],
  },
  {
    key: 'compliance.restrictions',
    version: 2,
    path: '/compliance/restricoes',
    tourId: 'compliance.restrictions',
    changes: [{ version: 2, items: ['Nova restrição de contato: o campo Credor lista só os credores do devedor, pedido do devedor e descadastro começam valendo para todos os credores e o quadro "Alcance" resume o que será bloqueado antes de salvar.'] }],
  },
  { key: 'compliance.contact-policy', version: 1, path: '/compliance/politica-de-contato', tourId: 'compliance.contact-policy' },
  { key: 'compliance.holidays', version: 1, path: '/compliance/feriados' },
  { key: 'compliance.legal-bases', version: 1, path: '/compliance/bases-legais', tourId: 'compliance.legal-bases' },
  { key: 'compliance.processors', version: 1, path: '/compliance/operadores', tourId: 'compliance.processors' },
  {
    key: 'compliance.requests',
    version: 2,
    path: '/compliance/solicitacoes-titulares',
    tourId: 'compliance.requests',
    changes: [{ version: 2, items: ['Vinda da ficha do devedor ou de uma solicitação, a fila mostra só os pedidos daquele titular; "Ver todas as solicitações" volta à fila completa.'] }],
  },
  {
    key: 'compliance.request-detail',
    version: 7,
    path: '/compliance/solicitacoes-titulares/:id',
    tourId: 'compliance.request-detail',
    changes: [
      { version: 2, items: ['O botão "Baixar dados do titular" aparece só para quem tem acesso a todos os credores: o arquivo traz o titular em todos eles, sem máscara.'] },
      {
        version: 3,
        items: ['Sem acesso a todos os credores, "Baixar dados do titular" aparece desabilitado e explica o motivo ao passar o mouse, tocar ou chegar pelo teclado.'],
      },
      { version: 4, items: ['Atribuir e Recusar em cinza ao lado da próxima ação (em azul); para voltar à lista, use a trilha no topo.'] }, { version: 5, items: ['Atribuir: a lista mostra só quem pode atender a solicitação (ativo, atende solicitações de titulares e com acesso a todos os credores), com a lista inteira visível no diálogo.'] },
      {
        version: 6,
        items: [
          'Confirmação de tratamento e informação sobre compartilhamento: cartão "Resposta sugerida" com credores, prestadores, escritórios e integrações, cada um com o link para a evidência.',
          'Alertas com atalho: prestador sem cadastro de operador e credor sem base legal vigente.',
          '"Usar como resposta" abre a conclusão com o texto preenchido e editável; nada é enviado sozinho.',
        ],
      },
      {
        version: 7,
        items: [
          'A resposta sugerida só é montada quando você clica em "Montar resposta sugerida" (a consulta lê dados do titular e fica registrada); "Montar de novo" refaz a consulta.',
          'Operadores cadastrados à mão (ex.: hospedagem) entram na tabela e no texto com a finalidade do cadastro.',
        ],
      }],
  },
  {
    key: 'compliance.retention',
    version: 4,
    path: '/compliance/retencao',
    tourId: 'compliance.retention',
    changes: [
      { version: 2, items: ['"Executar" só aparece para quem também atende titulares, porque a execução anonimiza dados; "Simular" continua para todos que gerenciam a retenção.'] },
      {
        version: 3,
        items: ['Sem a permissão de atender titulares, "Executar" volta a aparecer, desabilitado, e explica o motivo ao passar o mouse, tocar ou chegar pelo teclado.'],
      },
      {
        version: 4,
        items: [
          'Todas as categorias aparecem, inclusive as ainda não configuradas, com "Configurar".',
          'Os prazos do portal (pedidos de acesso vencidos, controle de tentativas, IP e navegador das sessões) e do score (histórico dos casos e fotografias para treino) agora ficam nesta tela: cada assessoria vê o padrão do sistema e pode ajustá-lo com o próprio encarregado de dados. Nessas categorias o descarte está sempre ativo; só o prazo muda, em dias.',
          '"Usar o padrão" apaga o prazo da assessoria e a categoria volta a seguir o padrão do sistema.',
        ],
      },
    ],
  },
  { key: 'compliance.vulnerability', version: 1, path: '/compliance/vulnerabilidade', tourId: 'compliance.vulnerability' },
  { key: 'compliance.complaints', version: 1, path: '/compliance/reclamacoes', tourId: 'compliance.complaints' },
  {
    key: 'compliance.complaint-config',
    version: 1,
    path: '/compliance/reclamacoes/configuracao',
    tourId: 'compliance.complaint-config',
  },
  { key: 'compliance.complaint-detail', version: 1, path: '/compliance/reclamacoes/:id', tourId: 'compliance.complaint-detail' },
  { key: 'organization.creditors', version: 1, path: '/organizacao/credores', tourId: 'organization.creditors' },
  {
    key: 'organization.creditor-detail',
    version: 3,
    path: '/organizacao/credores/:id',
    changes: [{ version: 3, items: ['Inativar credor passou para o menu "Mais ações" (⋯) no cabeçalho da ficha.'] }],
  },
  {
    key: 'organization.portfolios',
    version: 2,
    path: '/organizacao/carteiras',
    tourId: 'organization.portfolios',
    changes: [
      {
        version: 2,
        items: ['Regime da carteira: consumo (padrão, com as regras do CDC) ou empresarial (dívida entre empresas, sem o alerta de multa acima de 2% na revisão da carga e sem superendividamento). Mudar pede motivo e fica na auditoria.'],
      },
    ],
  },
  { key: 'organization.settings', version: 1, path: '/organizacao/configuracoes' },
  {
    key: 'governance.access-log',
    version: 3,
    path: '/governanca/log-de-acesso',
    tourId: 'governance.access-log',
    changes: [{ version: 2, items: ['O filtro por usuário só aparece para quem pode consultar o cadastro de usuários.'] }, { version: 3, items: ['A tabela cabe na largura da tela: IP e rota longos quebram dentro da célula, sem barra de rolagem lateral.'] }],
  },
  {
    key: 'governance.audit',
    version: 2,
    path: '/governanca/auditoria',
    tourId: 'governance.audit',
    changes: [{ version: 2, items: ['Os filtros por autor e por credor só aparecem para quem pode consultar usuários e credores, respectivamente.'] }],
  },
  { key: 'account.profile', version: 1, path: '/conta/perfil' },
  { key: 'account.password', version: 1, path: '/conta/senha' },
  { key: 'account.mfa', version: 1, path: '/conta/mfa' },
  { key: 'account.sessions', version: 1, path: '/conta/sessoes' },
  {
    key: 'platform.tenants',
    version: 2,
    path: '/plataforma/assessorias',
    changes: [{ version: 2, items: ['Reenviar o convite do administrador inicial enquanto a assessoria não tiver administrador ativo.'] }],
  },
  {
    key: 'platform.billing',
    version: 2,
    path: '/plataforma/faturamento',
    tourId: 'platform.billing',
    changes: [{ version: 2, items: ['Assessorias suspensas no ranking, com selo e valores zerados.', 'Chamadas não cobráveis e recusadas na autenticação, e atalho para os tokens de cada assessoria.'] }],
  },
  {
    key: 'platform.billing-tenant',
    version: 2,
    path: '/plataforma/faturamento/:tenantId',
    tourId: 'platform.billing-tenant',
    changes: [{ version: 2, items: ['Tráfego da API por credor e token: cobrável, não cobrável e recusado (com o motivo).', 'Controle dos tokens: pausar (com retomada automática opcional), retomar e revogar, um a um ou todos.', 'Assessoria suspensa: selo, valores zerados e sem extrato.'] }],
  },
  { key: 'publicapi.platform-charges', version: 1, path: '/integracoes/cobranca-plataforma', tourId: 'publicapi.platform-charges' },
  { key: 'app.pending-work', version: 1, path: '/pendencias' },
  { key: 'help.center', version: 1, path: '/ajuda' },
  { key: 'help.module', version: 1, path: '/ajuda/:modulo' },
];

/** Encontra a tela de uma URL (`/admin/usuarios/123?x=1` → `admin.user-detail`). */
export function findScreen(url: string, screens: readonly ScreenGuide[] = SCREENS): ScreenGuide | undefined {
  const path = normalize(url);
  const segments = path.split('/');
  return screens.find((screen) => {
    const pattern = normalize(screen.path).split('/');
    return pattern.length === segments.length && pattern.every((part, i) => part.startsWith(':') || part === segments[i]);
  });
}

function normalize(url: string): string {
  const path = url.split(/[?#]/)[0] ?? '/';
  const trimmed = path.length > 1 ? path.replace(/\/+$/, '') : path;
  return trimmed || '/';
}
