/** Contratos dos controllers do módulo communication (canais, templates, conversas e mensagens). */

export type ChannelType = 'WHATSAPP' | 'EMAIL' | 'SMS';
export type ProviderType = 'WHATSAPP_CLOUD' | 'SMTP' | 'SANDBOX';
export type OwnerType = 'ASSISTANT' | 'QUEUE' | 'USER' | 'NONE';
export type ConversationStatus = 'OPEN' | 'CLOSED';
export type MessageStatus = 'QUEUED' | 'SENT' | 'DELIVERED' | 'READ' | 'FAILED' | 'RECEIVED';
export type SenderType = 'USER' | 'ASSISTANT' | 'CAMPAIGN' | 'SYSTEM' | 'DEBTOR';
export type DraftStatus = 'PENDING' | 'SENT' | 'DISCARDED' | 'EXPIRED';
export type DraftMode = 'SHADOW' | 'COPILOT';
export type TemplateStatus = 'DRAFT' | 'PUBLISHED' | 'RETIRED';
export type ApprovalStatus = 'NOT_REQUIRED' | 'PENDING' | 'APPROVED' | 'REJECTED';
export type TemplatePurpose = 'COLLECTION' | 'REMINDER' | 'NOTIFICATION' | 'AGREEMENT' | 'PAYMENT' | 'SERVICE';
export type VariableType = 'FIRST_NAME' | 'MONEY' | 'DATE' | 'INTEGER' | 'URL' | 'CODE' | 'TEXT';
export type HandoffKind = 'ESCALATION' | 'TAKE_OVER' | 'ASSIGNMENT' | 'RELEASE' | 'RETURN_TO_ASSISTANT' | 'CLOSE' | 'REOPEN' | 'CASE_SYNC';

// --------------------------------------------------------------------- contas de canal

/** `ChannelAccountUseCases.AccountView`: credenciais nunca vêm; só os nomes das chaves configuradas. */
export interface AccountView {
  id: string;
  channel: ChannelType;
  provider: ProviderType;
  creditorId: string | null;
  name: string;
  senderAddress: string;
  senderName: string | null;
  replyTo: string | null;
  credentialKeys: string[];
  credentialsUpdatedAt: string | null;
  webhookPath: string | null;
  active: boolean;
  createdAt: string;
  version: number;
}

export interface CreateAccountRequest {
  channel: ChannelType;
  provider: ProviderType;
  creditorId: string | null;
  name: string;
  senderAddress: string;
  senderName: string | null;
  replyTo: string | null;
  credentials: Record<string, string> | null;
}

export interface UpdateAccountRequest {
  name: string;
  senderAddress: string;
  senderName: string | null;
  replyTo: string | null;
}

// --------------------------------------------------------------------- templates

export interface VariableInput {
  name: string;
  type: VariableType;
  required: boolean;
  maxLength: number | null;
}

export interface TemplateContent {
  name: string;
  purpose: TemplatePurpose;
  subject: string | null;
  body: string;
  variables: VariableInput[];
  language: string | null;
  providerTemplateName: string | null;
}

export interface CreateTemplateRequest {
  creditorId: string | null;
  code: string;
  channel: ChannelType;
  content: TemplateContent;
}

export interface ApprovalRequest {
  decision: 'PENDING' | 'APPROVED' | 'REJECTED';
  providerTemplateName: string | null;
  reference: string | null;
  note: string | null;
  decidedAt: string | null;
}

/** `TemplateUseCases.TemplateView` */
export interface TemplateView {
  id: string;
  creditorId: string | null;
  code: string;
  templateVersion: number;
  channel: ChannelType;
  name: string;
  purpose: TemplatePurpose;
  subject: string | null;
  body: string;
  variables: VariableInput[];
  language: string | null;
  status: TemplateStatus;
  providerTemplateName: string | null;
  approvalStatus: ApprovalStatus;
  approvalReference: string | null;
  approvalNote: string | null;
  approvalDecidedAt: string | null;
  approvalRegisteredBy: string | null;
  publishedAt: string | null;
  publishedBy: string | null;
  retiredAt: string | null;
  createdAt: string;
  version: number;
}

export interface TemplateFilter {
  creditorId: string | null;
  code: string | null;
  channel: string | null;
  status: string | null;
}

export interface TemplatePreview {
  subject: string | null;
  body: string;
  parameters: string[];
}

// --------------------------------------------------------------------- conversas

/** `ConversationUseCases.ConversationView` */
export interface ConversationView {
  id: string;
  debtorId: string | null;
  creditorId: string | null;
  portfolioId: string | null;
  caseId: string | null;
  channel: ChannelType;
  channelAccountId: string;
  contactMasked: string;
  status: ConversationStatus;
  ownerType: OwnerType;
  ownerUserId: string | null;
  ownerTeamId: string | null;
  requiredProfile: string | null;
  ownerSince: string | null;
  lastInboundAt: string | null;
  lastOutboundAt: string | null;
  lastMessageAt: string | null;
  awaitingSince: string | null;
  waitingSeconds: number | null;
  unreadCount: number;
  messageCount: number;
  sessionWindowOpen: boolean;
  sessionWindowEndsAt: string | null;
  closedAt: string | null;
  closeReason: string | null;
  createdAt: string;
  version: number;
  /** Meta de primeira resposta da assessoria para o canal (nulo sem espera). */
  slaStatus: SlaStatus | null;
  slaWarningAt: string | null;
  slaBreachAt: string | null;
}

export type SlaStatus = 'OK' | 'WARNING' | 'BREACHED';

/** `ConversationUseCases.InboxCounts`: abertas por aba e quantas estouraram a meta. */
export interface InboxCounts {
  mine: number;
  queue: number;
  assistant: number;
  all: number;
  breached: number;
}

/** `ResponseTargetUseCases.ResponseTargetView`. */
export interface ResponseTargetView {
  channel: ChannelType;
  warningMinutes: number;
  breachMinutes: number;
  customized: boolean;
  updatedAt: string | null;
}

export interface HandoffView {
  id: string;
  conversationId: string;
  caseId: string | null;
  fromOwnerType: OwnerType;
  fromUserId: string | null;
  toOwnerType: OwnerType;
  toUserId: string | null;
  toTeamId: string | null;
  kind: HandoffKind;
  reasonCode: string | null;
  summary: string | null;
  requiredProfile: string | null;
  actorType: string;
  actorId: string | null;
  referenceType: string | null;
  referenceId: string | null;
  occurredAt: string;
}

export interface DraftView {
  id: string;
  conversationId: string;
  mode: DraftMode;
  authorType: string;
  authorId: string | null;
  content: string;
  rationale: string | null;
  status: DraftStatus;
  decidedBy: string | null;
  decidedAt: string | null;
  edited: boolean;
  messageId: string | null;
  createdAt: string;
}

export interface ConversationDetail {
  conversation: ConversationView;
  handoffs: HandoffView[];
  pendingDrafts: DraftView[];
}

/** `MessageUseCases.MessageView` */
export interface MessageView {
  id: string;
  conversationId: string;
  channel: ChannelType;
  direction: 'INBOUND' | 'OUTBOUND';
  senderType: SenderType;
  senderId: string | null;
  templateId: string | null;
  subject: string | null;
  content: string | null;
  contentPurged: boolean;
  status: MessageStatus;
  statusAt: string | null;
  failureCode: string | null;
  failureDetail: string | null;
  sendAttempts: number;
  campaignExecutionId: string | null;
  queuedAt: string | null;
  sentAt: string | null;
  deliveredAt: string | null;
  readAt: string | null;
  failedAt: string | null;
  receivedAt: string | null;
  createdAt: string;
}

export interface InboxFilter {
  creditorId?: string | null;
  debtorId?: string | null;
  caseId?: string | null;
  channel?: string | null;
  status?: string | null;
  /** Donos separados por vírgula (`QUEUE,USER`). */
  owner?: string | null;
  /** `me` ou id do operador. */
  assignee?: string | null;
  requiredProfile?: string | null;
  awaitingReply?: boolean | null;
}

export interface SendMessageRequest {
  conversationId: string;
  templateCode: string | null;
  variables: Record<string, string> | null;
  text: string | null;
}
