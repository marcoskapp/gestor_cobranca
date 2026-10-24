import { ReleaseNote } from '../onboarding.models';

/** Central de novidades, da mais recente para a mais antiga. */
export const RELEASE_NOTES: readonly ReleaseNote[] = [
  {
    version: 6,
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
