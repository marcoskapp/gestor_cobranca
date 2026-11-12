import { ReleaseNote } from '../onboarding.models';

/** Central de novidades, da mais recente para a mais antiga. */
export const RELEASE_NOTES: readonly ReleaseNote[] = [
  {
    version: 31,
    date: '2026-11-12',
    title: 'Distribuição por desempenho',
    items: [
      { text: 'Nova tela "Classificação de desempenho": nível A a D de cada negociador pela qualidade (monitoria) e pela eficiência (recuperado × esperado), com a matriz parametrizável, revisão e ajuste com motivo pela supervisão e publicação pela gestão.', route: '/remuneracao/classificacao' },
      { text: 'No "Meu desempenho", o cartão "Seu nível de distribuição" mostra o seu nível e o que o compõe (quartis e base), sem a posição nem os colegas.', route: '/remuneracao/desempenho' },
      { text: 'Filas com estratégia "Por nível de desempenho": níveis altos recebem primeiro os casos de maior valor esperado e mais casos; 15% em rodízio.', route: '/cobranca/filas' },
      { text: 'Distribuição automática por horário em cada fila.', route: '/cobranca/filas' },
    ],
  },
  {
    version: 30,
    date: '2026-11-12',
    title: 'Margem por carteira e credor',
    items: [
      { text: 'Novo painel "Margem por carteira": receita, custo por categoria (pessoas, inteligência artificial, telefonia, mensagens, comissão, jurídico, ociosidade e custos fixos), margem de contribuição, margem líquida, custo por real recuperado e por acordo cumprido, por credor e carteira e mês a mês, com exportação.', route: '/paineis/margem' },
      { text: 'Nova configuração "Custos operacionais": faixas de custo por cargo e a faixa de cada pessoa (sem valor individual), tarifas de canal, câmbio do dólar para a inteligência artificial e custos fixos com a regra de rateio.', route: '/organizacao/custos' },
    ],
  },
  {
    version: 29,
    date: '2026-11-12',
    title: 'Histórico de acordos e ficha lateral mais legível',
    items: [
      { text: 'Aba "Acordos" no caso e na ficha do devedor: resumo (acordos, em vigor, cumprimento e total pago) e cada acordo com a situação e as parcelas, aberto numa aba nova.', route: '/negociacao/acordos' },
      { text: 'Ficha do devedor no painel ao lado da estação: listas em linhas com o resumo e "Mais detalhes", sem rolagem lateral (no celular também).', route: '/cobranca/fila' },
      { text: 'Recuperação judicial, extrajudicial e falência da empresa aparecem no selo de atenção especial e podem ser marcadas por ali.', route: '/devedores' },
    ],
  },
  {
    version: 28,
    date: '2026-11-11',
    title: 'Contato novo, cobrança de empresas e melhor forma de contato',
    items: [
      { text: 'Contato recebido numa nova carga para um devedor que já existia mostra o selo "Novo" até a primeira tentativa; filas, segmentos e a lista de devedores filtram por ele, e o discador liga primeiro para o número novo.', route: '/devedores' },
      { text: 'Prazo do selo "Novo" ajustável por assessoria (padrão de 30 dias).', route: '/devedores/contatos-recebidos' },
      { text: 'Empresas: nome fantasia, matriz e filiais, pessoa de contato com o papel dela e representantes que podem negociar; no portal, o acordo da empresa fecha com um representante confirmado ou passa a um atendente.', route: '/devedores' },
      { text: 'Carteira de consumo ou empresarial, e registro de recuperação judicial ou falência, que suspende a cobrança da empresa em todos os credores.', route: '/organizacao/carteiras' },
      { text: 'Cartão "Melhor forma de contato" no caso e na ficha do devedor, e a nova tela de inteligência de contato: melhor horário, canal preferido, tentativas até o atendimento e contatos até o acordo cumprido.', route: '/score/contato' },
    ],
  },
  {
    version: 27,
    date: '2026-11-11',
    title: 'Prazos de retenção num lugar só',
    items: [
      { text: 'Retenção de dados: todas as categorias aparecem, mesmo as ainda não configuradas, com "Configurar" para definir o prazo.', route: '/compliance/retencao' },
      { text: 'Os prazos de guarda dos dados de acesso ao portal e do histórico usado pelo score agora são ajustáveis por assessoria na mesma tela, partindo do padrão do sistema; nessas categorias o descarte não pode ser desligado. "Usar o padrão" volta ao prazo do sistema.', route: '/compliance/retencao' },
      { text: 'Resposta sugerida ao titular: montada só quando você pede ("Montar resposta sugerida") e com os operadores cadastrados à mão, como a hospedagem.', route: '/compliance/solicitacoes-titulares' },
    ],
  },
  {
    version: 26,
    date: '2026-11-11',
    title: 'Resposta sugerida ao titular e cadastro de operadores',
    items: [
      { text: 'Confirmação de tratamento e informação sobre compartilhamento: a solicitação mostra com quem os dados do titular foram compartilhados (credores, prestadores, escritórios e integrações), com link para cada evidência, e sugere a resposta, que você confere e edita antes de concluir.', route: '/compliance/solicitacoes-titulares' },
      { text: 'Novo cadastro de operadores e suboperadores: empresa, finalidade, país, garantia da transferência internacional e contrato; os prestadores em uso sem cadastro aparecem no topo.', route: '/compliance/operadores' },
      { text: 'Ficha do devedor: as solicitações LGPD do titular em "Relacionados", e a fila de solicitações filtrada pelo titular.', route: '/compliance/solicitacoes-titulares' },
      { text: 'Chamadas e gravações: filtro por devedor.', route: '/telefonia/chamadas' },
    ],
  },
  {
    version: 25,
    date: '2026-11-11',
    title: 'Atribuir só a quem pode atuar',
    items: [
      { text: 'Nos campos de responsável (casos, retornos, redistribuição, contestações, conversas, solicitações de titulares, ramais, monitoria, ocorrências, metas, alçadas e jurídico), a lista mostra só quem pode atuar naquele item, só pelo nome, e o sistema recusa quem estiver fora da regra.', route: '/cobranca/casos' },
      { text: 'Em diálogos, a lista de busca aparece inteira, por cima do diálogo, em vez de mostrar só a primeira opção.', route: '/compliance/solicitacoes-titulares' },
      { text: 'Tabelas sem barra de rolagem lateral: Log de acesso, Trilha de auditoria, Títulos, títulos do devedor e da ficha do caso (no painel estreito, o vencimento vai para a dica do atraso).', route: '/governanca/log-de-acesso' },
    ],
  },
  {
    version: 24,
    date: '2026-11-11',
    title: 'Resposta garantida a quem procurou e aviso no portal',
    items: [
      { text: 'Se a resposta a quem procurou não chegar ao devedor, a conversa volta a aguardar e a próxima resposta continua liberada; a liberação vale por até 72 h desde a mensagem do devedor.', route: '/conversas' },
      { text: 'Transferência pedida pelo assistente mantém o devedor aguardando um atendente, e o operador responde sem esbarrar nos limites da cobrança.', route: '/conversas' },
      { text: 'No portal, acima do limite diário de códigos, o devedor vê que pode pedir um novo código a partir de amanhã.', route: '/autonegociacao' },
      { text: 'Saúde das políticas: o uso do desconto ignora as faixas em que a política não prevê desconto.', route: '/negociacao/politicas/saude' },
    ],
  },
  {
    version: 23,
    date: '2026-11-11',
    title: 'Saúde das políticas e atalhos para a configuração',
    items: [
      { text: 'Saúde das políticas: quebra nos primeiros 60 dias, pago sobre acordado, uso do desconto e portal por carteira e faixa de atraso, com sugestões de revisão que você avalia antes de mudar a política.', route: '/negociacao/politicas/saude' },
      { text: 'Na política do assistente, atalhos para configurar perfis de alçada, canais e contas Pix (abrem em nova aba).', route: '/assistente/politicas' },
      { text: 'Importação PDF: escolha o credor antes da carteira; com o mapa salvo do credor em uso, "Salvar mapa" não aparece, e salvar um mapa ajustado substitui a versão anterior sem erro.', route: '/importacao/pdf/nova' },
      { text: 'Consumo da API: o gráfico "Por token" volta a mostrar as barras.', route: '/integracoes/consumo' },
    ],
  },
  {
    version: 22,
    date: '2026-11-11',
    title: 'Conversa com o primeiro nome preenchido e políticas mais legíveis',
    items: [
      { text: 'Na conversa, ao escolher um template, o primeiro nome do devedor já vem preenchido e a prévia aparece pronta.', route: '/conversas' },
      { text: 'O código de acesso ao portal não aparece mais na conversa, e o template dele tem finalidade própria ("Autenticação"), com texto curto só com o código.', route: '/conversas/templates' },
      { text: 'Nas políticas de negociação, cada sobreposição tem um nome legível na aba, e os campos das regras ficam alinhados.', route: '/negociacao/politicas' },
      { text: 'Quem já usa o portal: recrie os templates PORTAL_CODIGO_* com a finalidade "Autenticação (código de acesso)" (no WhatsApp, com nova aprovação da Meta); com outra finalidade, o código não é enviado.', route: '/conversas/templates' },
    ],
  },
  {
    version: 21,
    date: '2026-11-11',
    title: 'Portal do devedor: acesso a qualquer hora e resposta sem bloqueio',
    items: [
      { text: 'O código de acesso ao portal chega a qualquer hora, inclusive à noite, aos domingos e feriados, e pode ser pedido de novo logo em seguida; descadastro e restrições do devedor continuam valendo.', route: '/autonegociacao' },
      { text: 'Quem pediu atendimento pelo portal ou escreveu pelo WhatsApp recebe a resposta do operador sem esbarrar no intervalo mínimo da cobrança, e a caixa de entrada mostra há quanto tempo ele aguarda.', route: '/conversas' },
      { text: 'Em Pedidos do portal, "Abrir conversa" abre direto a conversa do pedido.', route: '/autonegociacao/pedidos' },
    ],
  },
  {
    version: 20,
    date: '2026-11-11',
    title: 'Monitoria: cada avaliador avalia um atendimento uma vez',
    items: [
      { text: 'O atendimento que você já avaliou mostra "Avaliado por você" com a nota no lugar de "Avaliar", no histórico do caso, na conversa e em Chamadas e gravações; outro avaliador ainda pode avaliá-lo.', route: '/remuneracao/qualidade/avaliacoes' },
    ],
  },
  {
    version: 19,
    date: '2026-11-10',
    title: 'Monitoria de qualidade mais rápida e perto do atendimento',
    items: [
      { text: 'Formulário de monitoria refeito: o atendimento avaliado num cartão, escala de 0 a 10 de um clique por critério e a nota ponderada sempre visível.', route: '/remuneracao/qualidade/avaliacoes' },
      { text: 'Ouça a gravação e avalie no mesmo passo; Chamadas e gravações filtra por operador.', route: '/telefonia/chamadas' },
      { text: 'No desempenho por negociador, a nota de monitoria abre as avaliações da pessoa e "Avaliar chamadas" abre as chamadas dela no período.', route: '/remuneracao/desempenho' },
    ],
  },
  {
    version: 18,
    date: '2026-11-08',
    title: 'Busca de devedor, fichas ligadas, aprovações em sequência e conformidade',
    items: [
      { text: 'Busca rápida (Ctrl + K) encontra devedor e caso por nome, CPF/CNPJ completo, telefone completo ou código do título; as fichas mostram o cartão "Relacionados" com os registros ligados que o seu perfil pode abrir.', route: '/devedores' },
      { text: 'Ficha do devedor com "Abrir caso", "Simular" e a aba "Interações" com os contatos de todos os casos.', route: '/devedores' },
      { text: 'Aprovações de propostas, pagamentos informados e correções de valor no mesmo formato: quanto passa da alçada, "Aprovar e abrir a próxima" e "1 de N".', route: '/negociacao/aprovacoes' },
      { text: '"Avaliar este atendimento" a partir da chamada, da conversa ou do histórico do caso; quem só consulta abre o caso sem a estação.', route: '/remuneracao/qualidade/avaliacoes' },
      { text: 'Os números dos painéis abrem a lista com os mesmos filtros quando ela é a base exata do indicador; no celular, os filtros das listas ficam no botão "Filtros (n)".', route: '/paineis' },
      { text: 'Marcador de vulnerabilidade e superendividamento, com selo na ficha e na estação e fila de atendimento cuidadoso; reclamações (Procon, consumidor.gov.br, Bacen, Reclame Aqui) com prazo e responsável; a contestação aberta pelo devedor no portal chega à fila de contestações.', route: '/compliance/reclamacoes' },
    ],
  },
  {
    version: 17,
    date: '2026-11-07',
    title: 'Abas que não cabem ficam em "Mais"',
    items: [
      { text: 'Quando as abas não cabem na largura (por exemplo, a ficha do devedor no painel da estação), as que sobram vão para o botão "Mais N ▾"; com uma delas aberta, o botão mostra o nome dela e quantas outras há.' },
      { text: 'Canal com o credor das contestações organizado por pedido, como uma conversa: o e-mail enviado ao credor e, logo abaixo, os recebidos.' },
    ],
  },
  {
    version: 16,
    date: '2026-11-07',
    title: 'Menu por tarefa e listas que voltam como estavam',
    items: [
      { text: 'O menu mostra só o que é tarefa do seu perfil; as seções recolhem com um clique no título e ficam assim nos outros dispositivos.' },
      { text: 'Filtros, página e ordenação das listas ficam no endereço: abra um registro, volte e a lista e a rolagem continuam onde estavam.', route: '/cobranca/casos' },
      { text: '"Voltar para…" no alto das fichas leva de volta à tela de onde você veio.' },
      { text: 'Casos, propostas e acordos filtram por devedor (nome ou CPF/CNPJ completo) e ordenam pelas colunas com seta.', route: '/negociacao/propostas' },
    ],
  },
  {
    version: 15,
    date: '2026-11-07',
    title: 'Atendimento e acordo sem sair do caso',
    items: [
      { text: 'Início com "Seu dia": próximo caso, retornos de hoje, promessas vencendo e o seu resultado; o supervisor vê as equipes e as filas.', route: '/' },
      { text: 'A estação guarda o atendimento de cada caso: abrir a ficha, o simulador ou outra tela e voltar não zera o cronômetro nem o rascunho.', route: '/cobranca/fila' },
      { text: 'Simulador e ficha do devedor num painel ao lado do caso; o acordo fecha ali: propor, aceite e formalização, Pix e o cartão "Acordo formalizado".', route: '/cobranca/fila' },
      { text: 'Simulador simula sozinho a partir do caso, com "Propor esta oferta" em cada oferta e parcelas como "Entrada + N parcelas".', route: '/negociacao/simulador' },
      { text: 'Caixa de entrada abre na Fila quando não há conversa sua aguardando.', route: '/conversas' },
    ],
  },
  {
    version: 14,
    date: '2026-11-07',
    title: 'Telas mais legíveis e organizadas',
    items: [
      { text: 'Texto mais firme e títulos em destaque; cantos e sombras mais discretos, em todos os temas.' },
      { text: 'Nas fichas, uma ação principal em azul e as demais no menu "Mais ações" (⋯), com a ação irreversível por último; para voltar, use a trilha no topo.', route: '/titulos' },
      { text: 'Indicadores com o mesmo cartão em todas as telas e valores compactos mais precisos ("R$ 1,25 mi").', route: '/paineis' },
      { text: 'No celular: listas em cartões com o nome, os principais dados e a situação; filtros no botão "Filtros"; tema e chat de suporte no menu do usuário.', route: '/devedores' },
      { text: 'Tela de entrada com a marca ao lado do formulário em telas largas.' },
    ],
  },
  {
    version: 13,
    date: '2026-11-05',
    title: 'Mensagem pela estação de cobrança',
    items: [
      { text: 'Botão Mensagem (Alt + W) na estação: inicie o contato por WhatsApp, SMS ou e-mail com um template aprovado, sem sair do caso.', route: '/cobranca/fila' },
      { text: 'Antes de enviar, a decisão do porteiro para cada contato; se o caso já tem conversa aberta no canal, um atalho leva até ela.', route: '/cobranca/fila' },
      { text: 'Aba Conversas na ficha do caso (Alt + 7): todas as conversas do caso, com quem atende e mensagens não lidas.', route: '/cobranca/fila' },
    ],
  },
  {
    version: 12,
    date: '2026-11-03',
    title: 'Entenda cada número sem sair da tela',
    items: [
      { text: 'Ícone "i" ao lado dos indicadores: o que é o valor, como é calculado e o que fazer quando ele sobe ou cai.', route: '/paineis' },
      { text: 'Abre ao passar o mouse, com Tab ou com um toque no celular; "Saiba mais" leva à parte certa do manual.', route: '/paineis/recuperacao' },
      { text: 'Tours dos painéis refeitos: cada passo mostra um indicador e como usá-lo na decisão.', route: '/paineis' },
      { text: 'O chat de suporte explica os mesmos números com os mesmos textos.' },
    ],
  },
  {
    version: 11,
    date: '2026-10-31',
    title: 'Seleção em massa na importação PDF',
    items: [
      { text: 'Filtre os títulos do PDF pela situação, com contadores, pelo devedor ou por texto.', route: '/importacao' },
      { text: 'Marque a página ou todos os títulos do filtro e exclua do lote com um motivo único, ou reinclua.', route: '/importacao' },
      { text: 'Títulos lidos com confiança baixa continuam confirmados um a um; excluí-los em massa é permitido.', route: '/importacao' },
    ],
  },
  {
    version: 10,
    date: '2026-10-30',
    title: 'Importação PDF inteligente',
    items: [
      { text: 'O credor só tem PDF? Envie o arquivo: o sistema encontra devedor, títulos e totais e monta o mapa de extração.', route: '/importacao/pdf/nova' },
      { text: 'Valide o mapa ao lado do PDF, com as regiões lidas em destaque e os totais do documento conferidos com a soma extraída.', route: '/importacao' },
      { text: 'Salve o mapa para o credor: os próximos PDFs do mesmo layout são lidos sem IA.', route: '/importacao' },
    ],
  },
  {
    version: 9,
    date: '2026-10-24',
    title: 'Conferência da importação',
    items: [
      { text: 'Nova importação em passos: nada é gravado até você conferir formato, linhas, totais e alertas.', route: '/importacao' },
      { text: 'Vírgula ou ponto decimal identificados coluna por coluna; valores ambíguos (1.000) só com a sua escolha, vendo exemplos do arquivo.', route: '/importacao' },
      { text: 'Confira a soma e a contagem da sua planilha: divergência bloqueia e aponta erro de separador decimal (×100, ÷100).', route: '/importacao' },
      { text: 'Alertas de valor pelo ticket médio do credor: confirme ou exclua do lote, com justificativa.', route: '/importacao' },
      { text: 'Importações de valor alto pedem a aprovação de outra pessoa.', route: '/importacao' },
    ],
  },
  {
    version: 8,
    date: '2026-10-24',
    title: 'Prestação de contas, telefonia e assistente de IA',
    items: [
      { text: 'Regras de comissão por contrato com versões, simulação e publicação por outra pessoa.', route: '/prestacao-de-contas/regras' },
      { text: 'Demonstrativos ao credor: cálculo, ajustes com segunda aprovação, aprovação segregada e arquivos com SHA-256.', route: '/prestacao-de-contas/demonstrativos' },
      { text: 'Repasses com comprovante e saldo a repassar por credor; razão de comissão com memória de cálculo.', route: '/prestacao-de-contas/repasses' },
      { text: 'Estação de cobrança: Ligar (Alt + L) com a decisão do porteiro, presença na telefonia e tabulação ao fim da chamada.', route: '/cobranca/fila' },
      { text: 'Campanhas de discagem com painel de ASR, abandono e operadores; gravações com finalidade declarada.', route: '/telefonia/campanhas' },
      { text: 'Assistente de IA: políticas por escopo, revisão de turnos e painel de qualidade.', route: '/assistente/politicas' },
      { text: 'Aprovação de pagamentos mostra o destino solicitado antes de aprovar.', route: '/pagamentos/aprovacoes' },
    ],
  },
  {
    version: 7,
    date: '2026-10-24',
    title: 'Painéis e relatórios da operação',
    items: [
      { text: 'Painel operacional: carteira, saldo vencido e elegível, casos por estado e fila pendente.', route: '/paineis' },
      { text: 'Recuperação líquida de estornos, com taxa sobre o saldo elegível, tempo e custo.', route: '/paineis/recuperacao' },
      { text: 'Funil do contato à 1ª parcela, acordado × pago e quebras por safra.', route: '/paineis/conversao' },
      { text: 'Produtividade por pessoa e equipe; o operador vê o próprio desempenho.', route: '/paineis/produtividade' },
      { text: 'Confiabilidade: falhas de integração, conciliação, processamentos e importações.', route: '/paineis/confiabilidade' },
    ],
  },
  {
    version: 6,
    date: '2026-10-23',
    title: 'Pagamentos, Pix e conciliação',
    items: [
      { text: 'Gerar Pix (imediato ou com vencimento) com QR Code e copia e cola, na ficha do acordo e na estação.', route: '/pagamentos/instrucoes' },
      { text: 'Pagamentos com destinos, linha do tempo e comprovante; informar pagamento externo com evidência.', route: '/pagamentos' },
      { text: 'Aprovação por outra pessoa acima do limite: quem informa não aprova.', route: '/pagamentos/aprovacoes' },
      { text: 'Fila de sobras para realocar ou registrar devolução ao pagador.', route: '/pagamentos/sobras' },
      { text: 'Conciliação por extrato CSV com prévia, indicadores do lote e divergências por tipo.', route: '/pagamentos/conciliacao' },
      { text: 'Contas Pix por credor com certificado mTLS, rotação de credenciais e webhook.', route: '/pagamentos/contas-pix' },
    ],
  },
  {
    version: 5,
    date: '2026-10-23',
    title: 'Campanhas, réguas e testes A/B',
    items: [
      { text: 'Segmentos com filtros combinados e contagem prévia do público.', route: '/campanhas/segmentos' },
      { text: 'Campanhas por credor com prioridade, exclusividade e régua versionada em etapas.', route: '/campanhas' },
      { text: 'Ativar, pausar e encerrar com o resumo de quantos devedores são alcançados.', route: '/campanhas' },
      { text: 'Painel de execução com falhas por causa e reprocessamento sem duplicar mensagens.', route: '/campanhas' },
      { text: 'Testes A/B com grupo de controle e intervalo de confiança por braço.', route: '/campanhas/testes' },
    ],
  },
  {
    version: 4,
    date: '2026-10-23',
    title: 'Propostas e acordos, caixa de entrada e jurídico',
    items: [
      { text: 'Do simulador, proponha dentro da alçada ou peça aprovação; aprovadores têm fila própria.', route: '/negociacao/aprovacoes' },
      { text: 'Proposta com memória de cálculo, aceite do devedor com evidência e termo em PDF.', route: '/negociacao/propostas' },
      { text: 'Acordos com cronograma de parcelas, cancelamento com motivo e renegociação.', route: '/negociacao/acordos' },
      { text: 'Aba Negociação na ficha do caso, na estação de cobrança (Alt + 6).', route: '/cobranca/fila' },
      { text: 'Caixa de entrada: conversas por dono, tempo de espera e linha do tempo única do devedor.', route: '/conversas' },
      { text: 'Sugestões do assistente para revisar, editar e enviar; templates e canais de envio.', route: '/conversas/templates' },
      { text: 'Jurídico: painel, casos por fase, viabilidade, autorização do credor, processos e prazos.', route: '/juridico' },
    ],
  },
  {
    version: 3,
    date: '2026-10-23',
    title: 'Estação de cobrança, negociação e importação',
    items: [
      { text: 'Estação de cobrança: próximo caso, ficha do caso e registro do atendimento em uma tela, com atalhos de teclado.', route: '/cobranca/fila' },
      { text: 'Janela de contato permitida por canal sempre visível durante o atendimento.', route: '/cobranca/fila' },
      { text: 'Agenda de retornos com atrasados em destaque.', route: '/cobranca/agenda' },
      { text: 'Gestão de filas, distribuição, tabulações e contestações com prazo.', route: '/cobranca/filas' },
      { text: 'Simulador com à vista e parcelado lado a lado, memória de cálculo e validação pela sua alçada.', route: '/negociacao/simulador' },
      { text: 'Políticas de cobrança versionadas, índices de correção e perfis de alçada.', route: '/negociacao/politicas' },
      { text: 'Importação de carteiras com prévia, progresso, ocorrências por linha e layouts por credor.', route: '/importacao' },
    ],
  },
  {
    version: 2,
    date: '2026-10-23',
    title: 'Devedores, títulos e compliance',
    items: [
      { text: 'Ficha do devedor com títulos, contatos mascarados, razão e titularidade.', route: '/devedores' },
      { text: 'Consulta de títulos por credor, carteira, situação e faixa de atraso.', route: '/titulos' },
      { text: 'Restrições de contato com evidência e política de contato versionada.', route: '/compliance/restricoes' },
      { text: 'Fila de solicitações de titulares (LGPD) com prazo legal em destaque.', route: '/compliance/solicitacoes-titulares' },
      { text: 'Retenção de dados com simulação antes da anonimização.', route: '/compliance/retencao' },
    ],
  },
  {
    version: 1,
    date: '2026-10-22',
    title: 'Nova identidade visual e ajuda integrada',
    items: [
      { text: 'Tema escuro e densidade compacta, escolhidos no menu do usuário e salvos na sua conta.' },
      { text: 'Busca rápida com Ctrl + K para chegar a qualquer tela pelo teclado.' },
      { text: 'Menu lateral recolhível e trilha de navegação no topo de cada tela.' },
      { text: 'Tours guiados e checklist de primeiros passos conforme o seu perfil.', route: '/' },
    ],
  },
];

export const LATEST_RELEASE = RELEASE_NOTES.reduce((max, note) => Math.max(max, note.version), 0);

/** Chave de "tela" usada para registrar a última versão de novidades lida. */
export const RELEASE_NOTES_SCREEN = 'app.release-notes';
