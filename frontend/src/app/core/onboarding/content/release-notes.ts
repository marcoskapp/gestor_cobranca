import { ReleaseNote } from '../onboarding.models';

/** Central de novidades, da mais recente para a mais antiga. */
export const RELEASE_NOTES: readonly ReleaseNote[] = [
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
