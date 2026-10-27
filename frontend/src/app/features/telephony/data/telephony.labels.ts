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
  'telephony_account.provider_not_ready': 'A integração com este provedor aguarda a homologação com o fornecedor ou está desligada neste ambiente.',
  'telephony_account.provider_disabled': 'A integração com este provedor está desligada neste ambiente. Peça ao administrador da plataforma.',
  'telephony_account.twilio_credentials_invalid': 'Credenciais da Twilio fora do formato: confira o Account SID (AC…), a API Key (SK…), o segredo e o Auth Token.',
  'telephony_account.plivo_credentials_invalid': 'Credenciais da Plivo fora do formato: confira o Auth ID (MA… ou SA…) e o Auth Token.',
  'agent_endpoint.twilio_invalid': 'Ramal da Twilio: client:identidade (softphone no navegador), sip:usuario@dominio ou +55….',
  'agent_endpoint.plivo_invalid': 'Ramal da Plivo: sip:usuario@phone.plivo.com (endpoint da Plivo) ou +55….',
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
  TWILIO: 'Twilio',
  PLIVO: 'Plivo',
};

export const READINESS_LABELS: Record<ProviderReadiness, string> = {
  READY: 'Pronto',
  DISABLED: 'Desligado neste ambiente',
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
  accountSid: 'Account SID (AC…)',
  apiKeySid: 'API Key SID (SK…)',
  apiKeySecret: 'Segredo da API Key',
  authToken: 'Auth Token',
  twimlAppSid: 'TwiML App SID (AP…, opcional: saída pelo navegador)',
  authId: 'Auth ID (MA… ou SA…)',
};

/** Chaves que não são segredo (campo de texto comum em vez de senha). */
export const PUBLIC_CREDENTIAL_KEYS: readonly string[] = ['apiBaseUrl', 'clientId', 'accountSid', 'apiKeySid', 'twimlAppSid', 'authId'];

/** Resultado do teste de conexão. */
export const CONNECTION_TEST_LABELS: Record<string, string> = {
  OK: 'Conexão confirmada: as credenciais foram aceitas pela plataforma.',
  UNSUPPORTED: 'Esta plataforma não oferece teste de conexão.',
  UNREACHABLE: 'Plataforma inacessível agora (rede ou instabilidade). Tente de novo em instantes.',
  TWILIO_API_KEY_REJECTED: 'A Twilio recusou a API Key (SID ou segredo). Confira no console e troque as credenciais.',
  TWILIO_AUTH_TOKEN_REJECTED: 'A Twilio recusou o Auth Token: os webhooks não seriam validados. Confira o token principal da conta.',
  PLIVO_AUTH_REJECTED: 'A Plivo recusou o Auth ID ou o Auth Token. Confira no console e troque as credenciais.',
};

/** Passo a passo curto por plataforma, mostrado no cadastro da conta. */
export const PROVIDER_SETUP_STEPS: Partial<Record<TelephonyProvider, readonly string[]>> = {
  TWILIO: [
    'No console da Twilio, copie o Account SID e o Auth Token (Account Info) e crie uma API Key padrão (Account → API keys).',
    'Compre ou porte um número brasileiro (exige CNPJ e endereço no Brasil) e informe-o como bina, em E.164 (+55…).',
    'Depois de salvar, cadastre no número a URL de voz e a de status mostradas na conta ("Endereços para o console").',
    'Ramais: client:identidade para atender no navegador (softphone) ou sip:/+55… para ramal externo.',
    'Use "Testar conexão" e, por fim, a regra de roteamento (padrão, credor, carteira ou campanha).',
  ],
  PLIVO: [
    'No console da Plivo, copie o Auth ID e o Auth Token (Overview).',
    'Alugue um número brasileiro e crie uma Application com as URLs de atendimento e de fim mostradas na conta depois de salvar.',
    'Crie um Endpoint por operador e cadastre o ramal sip:usuario@phone.plivo.com num softphone SIP (ou use +55…).',
    'Em Voice → Settings, ligue a autenticação HTTP das gravações.',
    'Use "Testar conexão" e defina a regra de roteamento.',
  ],
};

/** Formato do ramal por plataforma (dica no cadastro do ramal). */
export const ENDPOINT_HINTS: Partial<Record<TelephonyProvider, string>> = {
  TWILIO: 'client:identidade (atende no navegador), sip:usuario@dominio ou +55…',
  PLIVO: 'sip:usuario@phone.plivo.com (endpoint da Plivo) ou +55…',
};
