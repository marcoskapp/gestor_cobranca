import { TourDefinition } from '../onboarding.models';

/**
 * Tours guiados. Textos curtos (até ~25 palavras por passo), em pt-BR claro, orientados à ação.
 * Âncoras: atributo `data-tour` do shell (`nav`, `search`, `tenant`, `help`, `user-menu`) e dos
 * componentes compartilhados (`page-header`, `page-actions`, `filters`, `data-table`).
 */
export const TOURS: readonly TourDefinition[] = [
  {
    id: 'app.welcome',
    version: 1,
    title: 'Conheça o Gestor de Cobrança',
    summary: 'Menu, busca rápida, contexto da assessoria, ajuda e preferências em 1 minuto.',
    steps: [
      {
        title: 'Boas-vindas!',
        body: 'Em seis passos rápidos você conhece a navegação. Use as setas do teclado para avançar ou Esc para sair a qualquer momento.',
      },
      {
        anchor: 'nav',
        title: 'Menu principal',
        body: 'Mostra só o que o seu perfil pode usar. Recolha o menu para ganhar espaço na tela; os ícones continuam disponíveis.',
        placement: 'end',
      },
      {
        anchor: 'search',
        title: 'Busca rápida (Ctrl + K)',
        body: 'Digite parte do nome de uma tela ou ação e pressione Enter. É o caminho mais curto para qualquer lugar.',
      },
      {
        anchor: 'tenant',
        title: 'Onde você está trabalhando',
        body: 'Assessoria e credores do seu escopo. Tudo o que você vê e registra fica restrito a esse contexto.',
      },
      {
        anchor: 'help',
        title: 'Ajuda sempre à mão',
        body: 'Refaça tours, veja as novidades da versão, os primeiros passos e os atalhos de teclado.',
      },
      {
        anchor: 'user-menu',
        title: 'Aparência e conta',
        body: 'Escolha tema claro ou escuro e a densidade compacta para jornadas longas. Suas preferências acompanham você em qualquer dispositivo.',
      },
    ],
  },
  {
    id: 'admin.users',
    version: 1,
    title: 'Usuários',
    summary: 'Cadastro de contas, perfis de acesso e escopo de credores.',
    steps: [
      {
        anchor: 'page-header',
        title: 'Contas de acesso',
        body: 'Cada pessoa da assessoria tem uma conta própria. Contas não são compartilhadas: todas as ações ficam registradas em nome de quem fez.',
      },
      {
        anchor: 'page-actions',
        title: 'Novo usuário',
        body: 'Informe nome, e-mail e perfil. A pessoa recebe um link de ativação e define a própria senha.',
      },
      {
        anchor: 'filters',
        title: 'Filtros',
        body: 'Combine nome, situação e perfil. O resultado é atualizado enquanto você digita.',
      },
      {
        anchor: 'data-table',
        title: 'Lista de usuários',
        body: 'Situação e verificação em duas etapas aparecem em cada linha. Abra um usuário para ajustar perfis, escopo de credores ou bloquear o acesso.',
        placement: 'above',
      },
    ],
  },
  {
    id: 'admin.roles',
    version: 1,
    title: 'Perfis',
    summary: 'Conjuntos de permissões atribuídos aos usuários.',
    steps: [
      {
        anchor: 'page-header',
        title: 'Perfis de acesso',
        body: 'Um perfil reúne permissões de uma função (negociador, supervisor, financeiro). Prefira ajustar perfis a dar permissões caso a caso.',
      },
      {
        anchor: 'data-table',
        title: 'Perfis do sistema e personalizados',
        body: 'Perfis do sistema servem de ponto de partida. Crie perfis próprios para necessidades específicas da assessoria.',
        placement: 'above',
      },
    ],
  },
  {
    id: 'admin.teams',
    version: 1,
    title: 'Equipes',
    summary: 'Agrupamento de operadores com supervisor.',
    steps: [
      {
        anchor: 'page-header',
        title: 'Equipes de cobrança',
        body: 'Equipes definem quem supervisiona quem e são usadas na distribuição de casos e nas metas.',
      },
      {
        anchor: 'data-table',
        title: 'Membros e supervisor',
        body: 'Abra uma equipe para incluir operadores e definir o supervisor responsável.',
        placement: 'above',
      },
    ],
  },
  {
    id: 'debt.debtors',
    version: 1,
    title: 'Devedores',
    summary: 'Busca por nome ou documento e acesso à ficha do devedor.',
    steps: [
      {
        anchor: 'page-header',
        title: 'Devedores do seu escopo',
        body: 'Aparecem pessoas e empresas com títulos nos credores que você atende.',
      },
      {
        anchor: 'filters',
        title: 'Busque por nome ou documento',
        body: 'Digite parte do nome ou o CPF/CNPJ completo. O documento é enviado de forma protegida, fora do endereço da página.',
      },
      {
        anchor: 'data-table',
        title: 'Abra a ficha',
        body: 'Documentos aparecem mascarados. Na ficha ficam títulos, contatos, razão, titularidade e restrições.',
        placement: 'above',
      },
    ],
  },
  {
    id: 'debt.debtor-detail',
    version: 1,
    title: 'Ficha do devedor',
    summary: 'Posição, dados, títulos, contatos e histórico em um só lugar.',
    steps: [
      {
        anchor: 'debtor-summary',
        title: 'Posição do devedor',
        body: 'Saldo em cobrança, maior atraso e quantidade de títulos e credores.',
      },
      {
        anchor: 'document-reveal',
        title: 'Documento mascarado',
        body: 'Exibir o documento completo exige permissão e fica registrado no log de acesso. Use só quando necessário.',
      },
      {
        anchor: 'debtor-tabs',
        title: 'Títulos, contatos e histórico',
        body: 'Contatos podem ser confirmados ou marcados como inválidos. Razão e titularidade mostram cada lançamento e cada carteira.',
        placement: 'above',
      },
      {
        anchor: 'page-actions',
        title: 'Ações da ficha',
        body: 'Edite os dados cadastrais, veja os títulos na consulta completa ou baixe o dossiê de contato.',
      },
    ],
  },
  {
    id: 'debt.titles',
    version: 1,
    title: 'Títulos',
    summary: 'Consulta por credor, carteira, situação, atraso e vencimento.',
    steps: [
      {
        anchor: 'filters',
        title: 'Combine os filtros',
        body: 'Credor, carteira, situação e vencimento. A lista é atualizada enquanto você escolhe.',
      },
      {
        anchor: 'overdue-filter',
        title: 'Faixas de atraso',
        body: 'Faixas usuais da cobrança (1 a 30, 31 a 60 dias…). Consideram apenas títulos em cobrança.',
      },
      {
        anchor: 'data-table',
        title: 'Detalhe do título',
        body: 'Abra o título para ver saldo por componente, razão, titularidade e as ações permitidas.',
        placement: 'above',
      },
    ],
  },
  {
    id: 'debt.title-detail',
    version: 1,
    title: 'Detalhe do título',
    summary: 'Saldo, razão, titularidade e ciclo de vida do título.',
    steps: [
      {
        anchor: 'title-summary',
        title: 'Resumo financeiro',
        body: 'Saldo em aberto, valor original, total pago e atraso atual.',
      },
      {
        anchor: 'title-tabs',
        title: 'Saldo, razão e titularidade',
        body: 'O saldo é calculado do razão. Divergência com o saldo informado pelo credor aparece em destaque.',
        placement: 'above',
      },
      {
        anchor: 'title-actions',
        title: 'Ciclo de vida',
        body: 'Suspender, transferir, devolver ou cancelar pede motivo e fica na trilha de auditoria.',
      },
    ],
  },
  {
    id: 'compliance.restrictions',
    version: 1,
    title: 'Restrições de contato',
    summary: 'Descadastros, pedidos e bloqueios que impedem o contato.',
    steps: [
      {
        anchor: 'page-header',
        title: 'Restrições bloqueiam na hora',
        body: 'Uma restrição ativa impede ligações, mensagens e campanhas no alcance definido (credor, canal ou contato).',
      },
      {
        anchor: 'page-actions',
        title: 'Registrar restrição',
        body: 'Escolha devedor, tipo, origem e alcance. Depois anexe o comprovante do pedido.',
      },
      {
        anchor: 'filters',
        title: 'Encontre restrições',
        body: 'Filtre por devedor, credor, tipo, canal e situação.',
      },
      {
        anchor: 'data-table',
        title: 'Evidência e revogação',
        body: 'Anexe ou baixe o comprovante. Revogar exige motivo e fica registrado.',
        placement: 'above',
      },
    ],
  },
  {
    id: 'compliance.contact-policy',
    version: 1,
    title: 'Política de contato',
    summary: 'Janela, dias e limites de tentativas por canal, com versões.',
    steps: [
      {
        anchor: 'policy-scope',
        title: 'Assessoria ou credor',
        body: 'A política do credor prevalece canal a canal; canais sem regra seguem a assessoria.',
      },
      {
        anchor: 'policy-effective',
        title: 'Regras vigentes agora',
        body: 'Mostra a regra aplicada em cada canal e de onde ela vem.',
        placement: 'above',
      },
      {
        anchor: 'policy-draft',
        title: 'Rascunho da próxima versão',
        body: 'Ajuste janela, dias, limites e feriados. Nada muda até publicar.',
        placement: 'above',
      },
      {
        anchor: 'page-actions',
        title: 'Publicar',
        body: 'Cada publicação cria uma versão imutável, com vigência imediata ou agendada.',
      },
    ],
  },
  {
    id: 'compliance.legal-bases',
    version: 1,
    title: 'Bases legais',
    summary: 'Fundamento do tratamento e prazo de retenção por credor.',
    steps: [
      {
        anchor: 'filters',
        title: 'Escolha o credor',
        body: 'As bases legais são registradas por credor e, se necessário, por carteira.',
      },
      {
        anchor: 'page-actions',
        title: 'Registrar base legal',
        body: 'Informe a base do art. 7º, o contrato, o responsável jurídico e o prazo de retenção.',
      },
    ],
  },
  {
    id: 'compliance.requests',
    version: 1,
    title: 'Solicitações de titulares',
    summary: 'Fila de pedidos LGPD com prazo legal em destaque.',
    steps: [
      {
        anchor: 'page-actions',
        title: 'Registre no dia do recebimento',
        body: 'O prazo legal começa a contar no recebimento, por qualquer canal.',
      },
      {
        anchor: 'dsr-kpis',
        title: 'Situação da fila',
        body: 'Pedidos em atraso e em cada etapa. Clique em um indicador para filtrar.',
      },
      {
        anchor: 'data-table',
        title: 'Prazo em destaque',
        body: 'Pedidos vencidos aparecem em vermelho com ícone; os que vencem em até 3 dias, em amarelo.',
        placement: 'above',
      },
    ],
  },
  {
    id: 'compliance.request-detail',
    version: 1,
    title: 'Atendimento ao titular',
    summary: 'Etapas, ação automática, resposta e evidências.',
    steps: [
      {
        anchor: 'dsr-steps',
        title: 'Etapas do atendimento',
        body: 'Identidade confirmada, atendimento iniciado e conclusão com resposta ao titular.',
      },
      {
        anchor: 'dsr-next-action',
        title: 'Próxima ação',
        body: 'Aparece só a ação possível agora. Acesso e portabilidade geram arquivo; eliminação pede confirmação reforçada.',
      },
      {
        anchor: 'dsr-evidence',
        title: 'Evidências',
        body: 'Restrições criadas pelo pedido recebem o comprovante anexado.',
        placement: 'above',
      },
    ],
  },
  {
    id: 'compliance.retention',
    version: 1,
    title: 'Retenção de dados',
    summary: 'Prazos de guarda, simulação e anonimização.',
    steps: [
      {
        anchor: 'retention-policies',
        title: 'Políticas por categoria',
        body: 'Nascem desabilitadas. Defina o prazo e habilite quando a política estiver aprovada.',
      },
      {
        anchor: 'retention-dry-run',
        title: 'Simule antes',
        body: 'A simulação lista os devedores elegíveis sem alterar nenhum dado.',
      },
      {
        anchor: 'retention-runs',
        title: 'Histórico e evidência',
        body: 'Cada execução guarda o corte, os contadores e os itens processados.',
        placement: 'above',
      },
    ],
  },
  {
    id: 'organization.creditors',
    version: 1,
    title: 'Credores',
    summary: 'Empresas contratantes, contratos e carteiras.',
    steps: [
      {
        anchor: 'page-header',
        title: 'Credores',
        body: 'Empresas que contratam a assessoria. Cada credor tem contratos com regras de comissão e carteiras de títulos.',
      },
      {
        anchor: 'page-actions',
        title: 'Cadastrar credor',
        body: 'Comece pelo CNPJ e pelo nome. Contratos e carteiras são cadastrados na ficha do credor.',
      },
      {
        anchor: 'filters',
        title: 'Encontre rapidamente',
        body: 'Busque por nome, documento ou código externo. A lista mostra apenas credores do seu escopo.',
      },
      {
        anchor: 'data-table',
        title: 'Ficha do credor',
        body: 'Abra a ficha para ver contratos vigentes, carteiras e histórico de alterações.',
        placement: 'above',
      },
    ],
  },
  {
    id: 'organization.portfolios',
    version: 1,
    title: 'Carteiras',
    summary: 'Lotes de títulos recebidos dos credores e seu ciclo de vida.',
    steps: [
      {
        anchor: 'page-header',
        title: 'Carteiras',
        body: 'Uma carteira é um lote de títulos recebido de um credor, com vigência própria.',
      },
      {
        anchor: 'filters',
        title: 'Filtre por credor e situação',
        body: 'Carteiras em preparação ainda não estão em cobrança; ativas estão em operação; suspensas ou devolvidas não geram contatos.',
      },
      {
        anchor: 'data-table',
        title: 'Mudança de situação',
        body: 'Suspender ou devolver pede motivo e fica na trilha de auditoria. Encerrar é definitivo.',
        placement: 'above',
      },
    ],
  },
  {
    id: 'governance.access-log',
    version: 1,
    title: 'Log de acesso',
    summary: 'Quem entrou, quando, de onde e o que foi negado.',
    steps: [
      {
        anchor: 'page-header',
        title: 'Rastreabilidade de acesso',
        body: 'Logins, sessões, acessos negados e visualizações de dados sensíveis ficam registrados aqui.',
      },
      {
        anchor: 'filters',
        title: 'Investigue por período',
        body: 'Combine período, tipo de evento e resultado. Falhas de login repetidas merecem atenção.',
      },
      {
        anchor: 'data-table',
        title: 'Detalhe do evento',
        body: 'Abra um evento para ver origem, navegador e o contexto completo.',
        placement: 'above',
      },
    ],
  },
  {
    id: 'governance.audit',
    version: 1,
    title: 'Trilha de auditoria',
    summary: 'Alterações com autor, motivo e valores antes e depois.',
    steps: [
      {
        anchor: 'page-header',
        title: 'Trilha de auditoria',
        body: 'Toda alteração relevante registra quem fez, quando, o motivo e os valores antes e depois.',
      },
      {
        anchor: 'page-actions',
        title: 'Verificar integridade',
        body: 'Os registros são encadeados por hash. A verificação mostra se algum registro foi alterado fora da aplicação.',
      },
      {
        anchor: 'data-table',
        title: 'Detalhe da alteração',
        body: 'Abra um registro para comparar os valores antes e depois.',
        placement: 'above',
      },
    ],
  },
  {
    id: 'collection.station',
    version: 1,
    title: 'Estação de cobrança',
    summary: 'Fila, ficha do caso e registro do atendimento em uma só tela, pelo teclado.',
    steps: [
      {
        anchor: 'next-case',
        title: 'Próximo caso (Alt + N)',
        body: 'Entrega o caso mais prioritário das suas filas. Retornos vencidos com você vêm primeiro.',
      },
      {
        anchor: 'my-cases',
        title: 'Seus casos',
        body: 'Casos atribuídos a você, com saldo, atraso e próxima ação. Alt + Q recolhe a fila para ganhar espaço.',
        placement: 'end',
      },
      {
        anchor: 'case-tabs',
        title: 'Ficha do caso',
        body: 'Títulos, contatos mascarados, histórico, agenda e contestações. Alterne as abas com Alt + 1 a Alt + 5.',
      },
      {
        anchor: 'contact-window',
        title: 'Posso contatar agora?',
        body: 'Por canal, a decisão do sistema: permitido, aguardar (com o horário em que libera) ou restrito. Considera restrições, política, feriados e limites.',
        placement: 'start',
      },
      {
        anchor: 'interaction-form',
        title: 'Registrar interação (Alt + R)',
        body: 'A tabulação define o que é exigido: observação, promessa com valor e data ou retorno combinado. Ctrl + Enter registra.',
        placement: 'start',
      },
      {
        anchor: 'case-actions',
        title: 'Retorno, simulação e contestação',
        body: 'Alt + A agenda retorno e Alt + S abre a simulação. Contestação com evidência suspende a cobrança até a resolução.',
        placement: 'start',
      },
    ],
  },
  {
    id: 'collection.agenda',
    version: 1,
    title: 'Agenda de retornos',
    summary: 'Retornos combinados por dia, com atrasados em destaque.',
    steps: [
      {
        anchor: 'filters',
        title: 'Período',
        body: 'Hoje, próximos 7 dias, atrasados ou um período livre. Supervisores consultam a agenda de outro operador.',
      },
      {
        anchor: 'data-table',
        title: 'Retornos',
        body: 'Horário de Brasília. Conclua, reagende ou cancele e abra o caso direto na estação.',
        placement: 'above',
      },
    ],
  },
  {
    id: 'collection.cases',
    version: 1,
    title: 'Casos de cobrança',
    summary: 'Consulta de casos por credor, fila, responsável e elegibilidade.',
    steps: [
      {
        anchor: 'filters',
        title: 'Filtros',
        body: 'Combine credor, situação, fila, responsável e elegibilidade para encontrar exceções.',
      },
      {
        anchor: 'data-table',
        title: 'Casos',
        body: 'Inelegíveis mostram o motivo ao passar o mouse. Atribua a um operador ou equipe pelo ícone da linha.',
        placement: 'above',
      },
    ],
  },
  {
    id: 'collection.queues',
    version: 1,
    title: 'Filas e distribuição',
    summary: 'Critérios das filas, prioridade e distribuição entre operadores.',
    steps: [
      {
        anchor: 'page-actions',
        title: 'Filas',
        body: 'O caso entra na primeira fila ativa, por prioridade, cujos critérios satisfaz. Salvar uma fila reprocessa o roteamento.',
      },
      {
        anchor: 'data-table',
        title: 'Distribuir e redistribuir',
        body: 'Veja disponíveis e carga por operador e distribua em lote. Redistribua os casos de quem se ausentou.',
        placement: 'above',
      },
    ],
  },
  {
    id: 'collection.disputes',
    version: 1,
    title: 'Contestações',
    summary: 'Fila de contestações com prazo de resposta e resolução com parecer.',
    steps: [
      {
        anchor: 'dispute-kpis',
        title: 'Prazos',
        body: 'Prazo vencido, abertas e em análise. Clique num indicador para filtrar a lista.',
      },
      {
        anchor: 'data-table',
        title: 'Analisar e resolver',
        body: 'Abra a contestação para ver evidências, enviar para análise, resolver com parecer ou registrar desistência.',
        placement: 'above',
      },
    ],
  },
  {
    id: 'negotiation.simulator',
    version: 1,
    title: 'Simulador de negociação',
    summary: 'Dívida atualizada, ofertas lado a lado e validação das condições pela sua alçada.',
    steps: [
      {
        anchor: 'sim-context',
        title: 'Dívida a negociar',
        body: 'Escolha credor e devedor. Sem títulos marcados, entram todos os títulos em aberto no credor.',
        placement: 'end',
      },
      {
        anchor: 'sim-authority',
        title: 'Sua alçada',
        body: 'Seu limite aparece antes de simular: nível da escada, fatia do desconto, pagamentos e valor do acordo.',
        placement: 'end',
      },
      {
        anchor: 'sim-offers',
        title: 'À vista e parcelado',
        body: 'Compare as ofertas lado a lado. A situação indica se estão dentro da alçada ou exigem aprovação.',
      },
      {
        anchor: 'sim-escalate',
        title: 'Subir de nível',
        body: 'Concessões acima do nível 0 exigem o motivo, que fica registrado na auditoria.',
      },
      {
        anchor: 'sim-validate',
        title: 'Validar condições',
        body: 'Ajuste descontos e parcelas combinados e valide: dentro da alçada, requer aprovação ou rejeitado.',
        placement: 'above',
      },
    ],
  },
  {
    id: 'negotiation.policies',
    version: 1,
    title: 'Políticas de cobrança',
    summary: 'Versões das condições de negociação de cada credor.',
    steps: [
      {
        anchor: 'page-actions',
        title: 'Nova política',
        body: 'Comece pelo modelo de mercado e ajuste às condições do credor. A primeira versão nasce como rascunho.',
      },
      {
        anchor: 'data-table',
        title: 'Versões',
        body: 'Cada publicação cria uma versão imutável com vigência; a anterior é encerrada no dia anterior.',
        placement: 'above',
      },
    ],
  },
  {
    id: 'negotiation.policy-detail',
    version: 1,
    title: 'Versão da política',
    summary: 'Encargos, descontos, parcelamento, escada de concessões e sobreposições.',
    steps: [
      {
        anchor: 'policy-status',
        title: 'Situação e vigência',
        body: 'Rascunhos podem ser editados e publicados. Versões publicadas só podem ser aposentadas ou copiadas para uma nova versão.',
      },
      {
        anchor: 'policy-overrides',
        title: 'Regras e sobreposições',
        body: 'Sobreposições por carteira ou faixa de atraso substituem grupos inteiros das regras base. A mais específica vence.',
      },
      {
        anchor: 'policy-ladder',
        title: 'Escada de concessões',
        body: 'Cada nível libera uma fatia do desconto máximo. Negociadores começam no nível 0 e sobem com justificativa.',
      },
      {
        anchor: 'policy-versions',
        title: 'Linha do tempo',
        body: 'Todas as versões do credor, com vigência. Propostas guardam a versão usada.',
        placement: 'start',
      },
    ],
  },
  {
    id: 'negotiation.authority',
    version: 1,
    title: 'Alçadas',
    summary: 'Perfis de alçada e quem usa cada um.',
    steps: [
      {
        anchor: 'authority-tabs',
        title: 'Perfis e atribuições',
        body: 'O perfil define quanto do envelope da política o ator usa sem aprovação. Atribua por usuário, geral ou por credor.',
      },
      {
        anchor: 'page-actions',
        title: 'Atribuir alçada',
        body: 'Ninguém atribui a própria alçada. Toda mudança fica registrada no log de acesso.',
      },
    ],
  },
  {
    id: 'ingestion.batches',
    version: 1,
    title: 'Importação de carteiras',
    summary: 'Envio de arquivos do credor com prévia, progresso e ocorrências.',
    steps: [
      {
        anchor: 'page-actions',
        title: 'Modelo e envio',
        body: 'Baixe o modelo do layout padrão e envie primeiro em prévia: nada é gravado até você importar.',
      },
      {
        anchor: 'data-table',
        title: 'Lotes',
        body: 'Lotes em andamento mostram o progresso e se atualizam sozinhos. Abra um lote para ver as ocorrências por linha.',
        placement: 'above',
      },
    ],
  },
  {
    id: 'ingestion.batch-detail',
    version: 1,
    title: 'Lote de importação',
    summary: 'Contadores, ocorrências por linha e reprocessamento.',
    steps: [
      {
        anchor: 'batch-counters',
        title: 'Contadores',
        body: 'Linhas lidas, válidas e com erro, devedores e títulos criados ou atualizados.',
      },
      {
        anchor: 'batch-issues',
        title: 'Ocorrências',
        body: 'Erros e avisos por linha, com o valor mascarado. Baixe o relatório para devolver ao credor.',
      },
      {
        anchor: 'page-actions',
        title: 'Importar ou reprocessar',
        body: 'Depois da prévia, importe o mesmo arquivo. Reenviar não duplica títulos; arquivo já importado exige justificativa.',
      },
    ],
  },
];

export function findTour(id: string): TourDefinition | undefined {
  return TOURS.find((tour) => tour.id === id);
}
