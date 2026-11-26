import { REVERSAL_REASON_LABELS } from '../data/payment.labels';
import type { ReversalReason } from '../data/payment.models';
import type { HoldReleaseReason, HoldState } from './reversal-holds.api';

export const HOLD_STATE_LABELS: Record<HoldState, string> = {
  PENDING: 'Aguardando conferência',
  RELEASED: 'Cobrança liberada',
  ENDED: 'Encerrada (titular eliminado)',
};

export const HOLD_STATES = Object.keys(HOLD_STATE_LABELS) as HoldState[];

/** Motivo do estorno em texto (motivo desconhecido ou ausente: "Outro motivo"). */
export function reversalReasonText(reason: string | null | undefined): string {
  return (reason && REVERSAL_REASON_LABELS[reason as ReversalReason]) || 'Outro motivo';
}

export const HOLD_RELEASE_REASON_LABELS: Record<HoldReleaseReason, string> = {
  CREDITOR_CONFIRMED_NOT_RECEIVED: 'Credor confirmou que não recebeu',
  PAYMENT_ON_WRONG_TITLE: 'Pagamento informado no título errado',
  OTHER: 'Outro',
};

export const HOLD_RELEASE_REASONS = Object.keys(HOLD_RELEASE_REASON_LABELS) as HoldReleaseReason[];

/** Máximo da justificativa da liberação (servidor). */
export const HOLD_NOTE_MAX = 500;

/** Mensagens dos códigos de recusa da liberação, sem código interno (o servidor também revalida). */
export const HOLD_ERROR_LABELS: Record<string, string> = {
  'reversal_hold.self_release': 'Quem estornou ou informou o pagamento não pode liberar a cobrança. Peça a outra pessoa do financeiro.',
  'reversal_hold.confirmation_required': 'Marque que conferiu com o credor que o valor está em aberto.',
  'reversal_hold.release_note_required': 'Escreva a justificativa (até 500 caracteres).',
  'reversal_hold.release_reason_required': 'Escolha o motivo da liberação.',
  'reversal_hold.not_pending': 'A cobrança deste estorno já foi liberada ou encerrada. Atualize a página.',
  'reversal_hold.version_conflict': 'Este estorno mudou desde que você abriu a tela. Atualize a página.',
};

/** Idade da pendência em dias inteiros ("hoje", "há 1 dia", "há N dias"). */
export function holdAge(placedAt: string, now: Date = new Date()): string {
  const days = Math.max(0, Math.floor((now.getTime() - new Date(placedAt).getTime()) / 86_400_000));
  if (days === 0) {
    return 'hoje';
  }
  return days === 1 ? 'há 1 dia' : `há ${days} dias`;
}

/** Pendência antiga (mais de 5 dias, mesmo prazo da pendência vencida no servidor): pede destaque. */
export function holdOverdue(placedAt: string, now: Date = new Date(), days = 5): boolean {
  return now.getTime() - new Date(placedAt).getTime() > days * 86_400_000;
}
