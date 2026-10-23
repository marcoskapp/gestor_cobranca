import { ChannelType, HandoffKind, ProviderType, SenderType, TemplatePurpose, VariableType } from './communication.models';

export const CHANNEL_LABELS: Record<ChannelType, string> = { WHATSAPP: 'WhatsApp', EMAIL: 'E-mail', SMS: 'SMS' };
export const CHANNEL_ICONS: Record<ChannelType, string> = { WHATSAPP: 'chat', EMAIL: 'mail', SMS: 'sms' };

export const PROVIDER_LABELS: Record<ProviderType, string> = {
  WHATSAPP_CLOUD: 'WhatsApp Cloud API (Meta)',
  SMTP: 'E-mail da plataforma (SMTP)',
  SANDBOX: 'Simulador (somente testes)',
};

/** Canais atendidos por provedor e chaves de credencial exigidas (`ProviderType`). */
export const PROVIDER_CHANNELS: Record<ProviderType, readonly ChannelType[]> = {
  WHATSAPP_CLOUD: ['WHATSAPP'],
  SMTP: ['EMAIL'],
  SANDBOX: ['WHATSAPP', 'EMAIL', 'SMS'],
};
export const PROVIDER_CREDENTIALS: Record<ProviderType, readonly string[]> = {
  WHATSAPP_CLOUD: ['accessToken', 'appSecret', 'verifyToken'],
  SMTP: [],
  SANDBOX: [],
};
export const CREDENTIAL_LABELS: Record<string, string> = {
  accessToken: 'Token de acesso',
  appSecret: 'Segredo do aplicativo',
  verifyToken: 'Token de verificação do webhook',
};

export const SENDER_LABELS: Record<SenderType, string> = {
  USER: 'Operador',
  ASSISTANT: 'Assistente',
  CAMPAIGN: 'Campanha',
  SYSTEM: 'Sistema',
  DEBTOR: 'Devedor',
};

export const PURPOSE_LABELS: Record<TemplatePurpose, string> = {
  COLLECTION: 'Cobrança',
  REMINDER: 'Lembrete',
  NOTIFICATION: 'Notificação',
  AGREEMENT: 'Acordo',
  PAYMENT: 'Pagamento',
  SERVICE: 'Atendimento',
};

export const VARIABLE_TYPE_LABELS: Record<VariableType, string> = {
  FIRST_NAME: 'Primeiro nome',
  MONEY: 'Valor (R$)',
  DATE: 'Data',
  INTEGER: 'Número inteiro',
  URL: 'Link (https)',
  CODE: 'Código (protocolo, linha digitável)',
  TEXT: 'Texto curto (sem dados pessoais)',
};

/** Exemplo de preenchimento por tipo (prévia do template). */
export const VARIABLE_EXAMPLES: Record<VariableType, string> = {
  FIRST_NAME: 'Ana',
  MONEY: '1234.56',
  DATE: '2026-11-10',
  INTEGER: '6',
  URL: 'https://pagamento.exemplo.com.br/abc',
  CODE: '34191790010104351004791020150008291070026000',
  TEXT: 'Loja Sol',
};

export const HANDOFF_KIND_LABELS: Record<HandoffKind, string> = {
  ESCALATION: 'Transferida para humano',
  TAKE_OVER: 'Assumida',
  ASSIGNMENT: 'Atribuída',
  RELEASE: 'Devolvida à fila',
  RETURN_TO_ASSISTANT: 'Devolvida ao assistente',
  CLOSE: 'Encerrada',
  REOPEN: 'Reaberta',
  CASE_SYNC: 'Dono alinhado ao caso',
};

/** Motivos do porteiro de contato (`ContactGate.DenialReason`) em linguagem clara. */
export const DENIAL_REASON_LABELS: Record<string, string> = {
  RESTRICTED_DEBTOR: 'o devedor pediu para não ser contatado',
  RESTRICTED_CHANNEL: 'há restrição de contato neste canal',
  RESTRICTED_CONTACT_VALUE: 'este número ou endereço está bloqueado para contato',
  OUTSIDE_WINDOW: 'está fora do horário permitido para contato',
  HOLIDAY: 'hoje é feriado e não é permitido contatar',
  DAILY_LIMIT: 'o limite de contatos do dia já foi atingido',
  WEEKLY_LIMIT: 'o limite de contatos da semana já foi atingido',
  MIN_INTERVAL: 'o intervalo mínimo desde o último contato ainda não passou',
  DEBTOR_ANONYMIZED: 'os dados do devedor foram anonimizados',
};

/** Falhas de envio além do porteiro (`Message.failureCode`). */
export const FAILURE_LABELS: Record<string, string> = {
  CONTACT_DENIED: 'Envio bloqueado pela política de contato',
  ACCOUNT_UNAVAILABLE: 'Conta do canal indisponível',
  WHATSAPP_WINDOW_CLOSED: 'Janela de 24 h do WhatsApp fechada: use um template',
  RETRIES_EXHAUSTED: 'O provedor não respondeu após várias tentativas',
};

/** Motivos de transferência para humano (`reasonCode`; os demais aparecem como recebidos). */
export const HANDOFF_REASON_LABELS: Record<string, string> = {
  REQUIRES_APPROVAL: 'Proposta acima da alçada do assistente',
  DISPUTE: 'Devedor contesta a dívida',
  DEBTOR_REQUESTED_HUMAN: 'Devedor pediu atendimento humano',
  UNSPECIFIED: 'Motivo não informado',
};
