/**
 * Chat de suporte com IA (módulo `helpdesk` do backend, docs/tecnico/desenho/chat-suporte.md §7).
 * O navegador envia só a chave da tela atual e o texto da pergunta; o manual que orienta a IA vem do
 * servidor. Cartões e ações são montados pelo servidor a partir das consultas feitas como o próprio
 * usuário.
 */

export interface SupportChatStatus {
  available: boolean;
  ticketsAvailable: boolean;
  ticketsUnavailableReason: string | null;
  questionsToday: number;
  questionsPerDay: number;
  knowledgeVersion: string;
}

export interface SupportCardField {
  label: string;
  value: string;
}

export type SupportCardKind =
  | 'DEBTOR'
  | 'CASE'
  | 'AGREEMENT'
  | 'PAYMENT'
  | 'IMPORT'
  | 'SETTLEMENT_STATEMENT'
  | 'COMMISSION_STATEMENT'
  | 'PENDING';

export interface SupportCard {
  ref: string | null;
  kind: SupportCardKind;
  title: string | null;
  subtitle: string | null;
  status: string | null;
  fields: SupportCardField[];
  route: string | null;
  queryParams: Record<string, string>;
}

export type SupportActionKind = 'OPEN_SCREEN' | 'START_TOUR' | 'ABOUT_SCREEN';

export interface SupportAction {
  kind: SupportActionKind;
  screen: string;
  label: string;
  route: string | null;
}

export type SupportTicketStatus = 'DRAFT' | 'CREATING' | 'OPENED' | 'DUPLICATE' | 'FAILED' | 'CANCELLED' | 'EXPIRED';

export interface SupportTicket {
  id: string;
  status: SupportTicketStatus;
  title: string;
  body: string;
  labels: string[];
  repository: string;
  issueNumber: number | null;
  issueUrl: string | null;
  errorCode: string | null;
  createdAt: string;
  expiresAt: string;
}

export type SupportOutcome = 'ANSWERED' | 'UNANSWERED' | 'OUT_OF_SCOPE' | 'FAILED';
export type SupportRating = 'UP' | 'DOWN';
export type SupportDownReason = 'INCORRECT' | 'NOT_HELPFUL' | 'INCOMPLETE' | 'OUT_OF_DATE' | 'OTHER';

export interface SupportMessage {
  id: string;
  role: 'USER' | 'ASSISTANT';
  text: string | null;
  cards: SupportCard[];
  actions: SupportAction[];
  ticket: SupportTicket | null;
  outcome: SupportOutcome | null;
  feedback: SupportRating | null;
  purged: boolean;
  createdAt: string;
}

export interface SupportAnswer {
  conversationId: string;
  question: SupportMessage;
  answer: SupportMessage;
}

export interface SupportConversation {
  id: string;
  startedAt: string;
  lastActivityAt: string;
  turns: number;
  messages: SupportMessage[];
}

export const SUPPORT_QUESTION_MAX = 2000;

export const DOWN_REASONS: readonly { value: SupportDownReason; label: string }[] = [
  { value: 'INCORRECT', label: 'Incorreta' },
  { value: 'NOT_HELPFUL', label: 'Não ajudou' },
  { value: 'INCOMPLETE', label: 'Incompleta' },
  { value: 'OUT_OF_DATE', label: 'Desatualizada' },
];
