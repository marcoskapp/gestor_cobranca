import { CallResult, DialerMode, PauseReason, RecordingPurpose } from './telephony.models';

export const PAUSE_REASON_LABELS: Record<PauseReason, string> = {
  BREAK: 'Intervalo',
  LUNCH: 'Refeição',
  RESTROOM: 'Banheiro',
  TRAINING: 'Treinamento',
  FEEDBACK: 'Feedback',
  MEETING: 'Reunião',
  BACKOFFICE: 'Retaguarda',
  TECHNICAL_ISSUE: 'Problema técnico',
  OTHER: 'Outro motivo',
};

/** Pausas produtivas (contam como trabalho nas métricas da operação). */
export const PRODUCTIVE_PAUSES: readonly PauseReason[] = ['TRAINING', 'FEEDBACK', 'MEETING', 'BACKOFFICE'];

export const CALL_RESULT_LABELS: Record<CallResult, string> = {
  ANSWERED: 'Atendida',
  NO_ANSWER: 'Não atendeu',
  BUSY: 'Ocupado',
  VOICEMAIL: 'Caixa postal',
  FAILED: 'Falhou',
  CANCELLED: 'Cancelada',
  ABANDONED: 'Abandonada (sem operador)',
};

export const RECORDING_PURPOSE_LABELS: Record<RecordingPurpose, string> = {
  QUALITY_MONITORING: 'Monitoria de qualidade',
  DISPUTE: 'Contestação do devedor',
  DEBTOR_REQUEST: 'Pedido do titular (LGPD)',
  LEGAL: 'Processo judicial ou jurídico',
  AUDIT: 'Auditoria',
};

export const DIALER_MODE_LABELS: Record<DialerMode, string> = {
  PROGRESSIVE: 'Progressivo',
  PREDICTIVE: 'Preditivo',
};

export const DIALER_MODE_HINTS: Record<DialerMode, string> = {
  PROGRESSIVE: 'Uma chamada por operador livre. Sem abandono: ninguém atende sem operador.',
  PREDICTIVE: 'Disca mais linhas que operadores livres, pela taxa de atendimento do dia, limitado pela razão máxima e pelo abandono.',
};

/** Motivo da razão de discagem atual (`DialerPacing`). */
export const PACING_REASON_LABELS: Record<string, string> = {
  progressive: 'Progressivo: uma linha por operador livre',
  warming_up: 'Aquecimento (menos de 30 discagens): razão 1',
  predictive: 'Preditivo pela taxa de atendimento do dia',
  abandon_near_limit: 'Abandono perto do máximo: razão reduzida',
  abandon_limit: 'Abandono no máximo: volta ao progressivo até baixar',
  no_agents: 'Nenhum operador livre',
  lines_busy: 'Linhas ocupadas: aguardando chamadas terminarem',
};

export const EXCLUDED_REASON_LABELS: Record<string, string> = {
  CASE_CLOSED: 'Caso encerrado',
  CASE_ASSIGNED: 'Caso atribuído a operador',
  CASE_INELIGIBLE: 'Caso inelegível',
  NO_PHONE: 'Sem telefone válido',
  CONTACT_RESTRICTED: 'Restrição de contato',
  CAMPAIGN_FINISHED: 'Campanha encerrada',
};

export const HANGUP_SIDE_LABELS: Record<string, string> = {
  DEBTOR: 'devedor',
  OPERATOR: 'operador',
  SYSTEM: 'sistema',
  PROVIDER: 'provedor',
};

/** Mensagens das recusas de regra que as telas apresentam por conta própria. */
export const TELEPHONY_ERROR_LABELS: Record<string, string> = {
  'telephony.agent_not_configured': 'Você ainda não tem ramal na telefonia. Peça ao gestor para habilitar o seu usuário.',
  'telephony.not_configured': 'A telefonia não está configurada para este credor. Peça ao administrador.',
  'agent.not_available': 'Fique disponível na telefonia antes de ligar (ou tabule a chamada anterior).',
  'agent.inactive': 'Seu ramal está desabilitado. Peça ao gestor para reativá-lo.',
  'agent.offline': 'Entre na telefonia antes de continuar.',
  'agent.on_call': 'Você está em chamada. Termine a chamada primeiro.',
  'agent.cannot_pause': 'Pausa só quando disponível. No pós-atendimento, tabule primeiro.',
  'call.case_closed': 'O caso está encerrado: não é possível ligar.',
  'call.contact_not_found': 'O telefone escolhido não pertence ao devedor ou está inválido.',
  'call.user_required': 'O clique para ligar é exclusivo de operadores.',
  'call.already_tabulated': 'A chamada já foi tabulada (talvez pelo prazo automático).',
  'call.in_progress': 'A chamada ainda está em andamento: tabule depois de encerrar.',
  'call.not_active': 'A chamada já terminou.',
  'call.tabulation_required': 'Escolha a tabulação.',
  'call.case_required': 'Informe o caso da chamada recebida.',
  'recording.unavailable': 'A gravação não está disponível (ainda copiando, falhou ou foi descartada pela retenção).',
  'dialer_campaign.code_taken': 'Já existe campanha de discagem com este código.',
  'dialer_campaign.active': 'Pause a campanha para alterar os parâmetros.',
  'dialer_campaign.not_activatable': 'Somente campanhas em rascunho ou pausadas podem ser ativadas.',
};
