import { CallResult, DialerMode, PauseReason, ProviderReadiness, RecordingPurpose, RouteScope, TelephonyProvider } from './telephony.models';

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

/** Tabulações automáticas do catálogo (`DefaultTabulations`), quando a assessoria não as renomeou. */
export const AUTO_TABULATION_LABELS: Record<string, string> = {
  CALL_ANSWERED: 'atendida sem tabulação do operador',
  CALL_ABANDONED: 'chamada abandonada',
  CALL_FAILED: 'chamada com falha',
  NO_ANSWER: 'não atende',
  BUSY: 'ocupado',
  VOICEMAIL: 'caixa postal',
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
  'telephony.agent_not_on_account': 'O operador não tem ramal na plataforma de telefonia que atende este caso ou campanha. Peça ao gestor para cadastrar o ramal nessa conta.',
  'telephony_account.provider_unavailable': 'A integração com este provedor ainda não está disponível.',
  'telephony_account.provider_not_ready': 'A integração com este provedor aguarda a homologação com o fornecedor.',
  'telephony_account.sandbox_disabled': 'O provedor simulado só existe em ambiente local ou de teste.',
  'telephony_account.alvaria_url_insecure': 'O endereço da API da Alvaria precisa usar HTTPS.',
  'telephony_account.alvaria_host_not_allowed': 'Endereço da API fora dos domínios permitidos para a Alvaria. Confira o endereço da instância.',
  'telephony_account.alvaria_url_invalid': 'Endereço da API inválido.',
  'telephony_account.recording_notice_required': 'Com gravação, o aviso ao devedor é obrigatório.',
  'telephony_account.caller_id_invalid': 'Número de origem inválido (8 a 15 dígitos, com ou sem +).',
  'telephony_account.rate_invalid': 'Tarifa por minuto inválida (0 a 100, até 4 casas).',
  'telephony_route.account_other_creditor': 'Esta conta é restrita a outro credor.',
  'telephony_route.fallback_same': 'A contingência precisa ser outra conta.',
  'agent_endpoint.extension_required': 'Informe o ramal do operador nesta conta.',
  'agent.extension_invalid': 'Ramal inválido (2 a 64 caracteres: letras, dígitos e . _ - @ : +).',
};

export const PROVIDER_LABELS: Record<TelephonyProvider, string> = {
  SANDBOX: 'Simulador',
  ALVARIA: 'Alvaria CX (Aspect)',
  TARIFANDO: 'Tarifando Telecom',
};

export const READINESS_LABELS: Record<ProviderReadiness, string> = {
  READY: 'Pronto',
  CONTRACT_PENDING: 'Aguarda homologação',
  NOT_IMPLEMENTED: 'Integração indisponível',
};

export const ROUTE_SCOPE_LABELS: Record<RouteScope, string> = {
  CAMPAIGN: 'Campanha de discagem',
  PORTFOLIO: 'Carteira',
  CREDITOR: 'Credor',
  DEFAULT: 'Padrão da assessoria',
};

/** Ordem de aplicação das regras (da mais específica para a mais ampla). */
export const ROUTE_SCOPE_ORDER: readonly RouteScope[] = ['CAMPAIGN', 'PORTFOLIO', 'CREDITOR', 'DEFAULT'];

export const TELEPHONY_CREDENTIAL_LABELS: Record<string, string> = {
  webhookSecret: 'Segredo dos eventos (webhook)',
  apiBaseUrl: 'Endereço da API da instância',
  clientId: 'Client ID (OAuth)',
  clientSecret: 'Client secret (OAuth)',
  apiToken: 'Token da API',
};

/** Chaves que não são segredo (campo de texto comum em vez de senha). */
export const PUBLIC_CREDENTIAL_KEYS: readonly string[] = ['apiBaseUrl', 'clientId'];
