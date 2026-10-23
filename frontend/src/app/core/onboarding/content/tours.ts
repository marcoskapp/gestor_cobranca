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
];

export function findTour(id: string): TourDefinition | undefined {
  return TOURS.find((tour) => tour.id === id);
}
