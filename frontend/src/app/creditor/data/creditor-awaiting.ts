import { Injectable, inject } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router } from '@angular/router';
import { distinctUntilChanged, filter, map } from 'rxjs';
import { CreditorOverviewApi } from './creditor-overview.api';
import { CreditorSession } from './creditor-session';
import {
  CREDITOR_DISPUTE_TYPE_LABELS,
  CREDITOR_REJECTION_REASON_LABELS,
  CreditorAwaiting,
  CreditorAwaitingItem,
  CreditorAwaitingKind,
  CreditorAwaitingSource,
  CreditorPermission,
} from './creditor.models';

/** Quantos itens o Início mostra antes de "Ver todas". */
export const AWAITING_PREVIEW = 5;

/**
 * "Aguardando você" do portal do credor (`GET /api/creditor/v1/awaiting`), lido uma vez por tela e compartilhado pela
 * moldura (contador do item "Contestações") e pelo Início (cartão e lista). Relido quando o **caminho** muda: responder
 * numa ficha e voltar já atualiza, mas trocar só a consulta ou o fragmento (ex.: `?periodo=` no Início) não relê.
 * Sem a visão geral do portal, nenhuma chamada sai; trocar de pessoa relê.
 */
@Injectable({ providedIn: 'root' })
export class CreditorAwaitingState {
  private readonly session = inject(CreditorSession);
  private readonly api = inject(CreditorOverviewApi);
  private readonly router = inject(Router);
  private readonly path = toSignal(
    this.router.events.pipe(
      filter((e): e is NavigationEnd => e instanceof NavigationEnd),
      map((e) => pathOf(e.urlAfterRedirects)),
      distinctUntilChanged(),
    ),
    { initialValue: pathOf(this.router.url) },
  );

  readonly resource = rxResource<CreditorAwaiting, { user: string; path: string } | undefined>({
    params: () => {
      const me = this.session.me();
      return me && this.session.has(CreditorPermission.OVERVIEW) ? { user: me.userId, path: this.path() } : undefined;
    },
    stream: () => this.api.awaiting(),
  });
}

/** Caminho de um endereço do roteador, sem a consulta (`?…`) nem o fragmento (`#…`). */
export function pathOf(url: string): string {
  return url.split(/[?#]/)[0];
}

/** Quem do time do credor resolve cada pendência (perfis padrão do portal), para quem não pode resolver. */
export const AWAITING_WHO: Record<CreditorAwaitingKind, string> = {
  DISPUTE: 'Quem responde: Gestão de recuperação ou Jurídico.',
  STATEMENT: 'Quem responde: Financeiro.',
  PAYMENT_REJECTED: 'Quem informa: Gestão de recuperação ou Financeiro.',
};

/** Permissões que resolvem algum tipo de pendência (sem nenhuma, o bloco vazio é "do seu time"). */
export const AWAITING_PERMISSIONS = [
  CreditorPermission.DISPUTE_RESPOND,
  CreditorPermission.STATEMENT_RESPOND,
  CreditorPermission.PAYMENT_REPORT,
] as const;

/** Fonte fora do ar, como o credor a entende ("Não foi possível carregar as contestações"). */
export const AWAITING_SOURCE_LABELS: Record<CreditorAwaitingSource, string> = {
  DISPUTES: 'as contestações',
  STATEMENTS: 'os demonstrativos',
  PAYMENTS: 'os pagamentos recusados',
};

/** "as contestações e os demonstrativos". */
export function unavailableText(sources: readonly CreditorAwaitingSource[]): string {
  const names = sources.map((s) => AWAITING_SOURCE_LABELS[s] ?? 'parte das pendências');
  return names.length <= 1 ? (names[0] ?? '') : `${names.slice(0, -1).join(', ')} e ${names.at(-1)}`;
}

/** Data local de hoje (yyyy-MM-dd), no fuso do navegador. */
export function localToday(now = new Date()): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
}

/** "dd/mm" de uma data (yyyy-MM-dd) ou de um instante (no fuso do navegador). */
export function dayMonth(value: string | null | undefined): string {
  if (!value) {
    return '';
  }
  const date = /^\d{4}-\d{2}-\d{2}$/.test(value) ? value : localToday(new Date(value));
  const [, m, d] = date.split('-');
  return `${d}/${m}`;
}

/** Título do item, sem códigos internos. */
export function awaitingTitle(item: CreditorAwaitingItem): string {
  switch (item.kind) {
    case 'DISPUTE':
      return `Contestação · protocolo ${item.dispute?.protocol ?? ''} · ${CREDITOR_DISPUTE_TYPE_LABELS[item.dispute?.type ?? ''] ?? 'Contestação'}`;
    case 'STATEMENT':
      return `Demonstrativo nº ${item.statement?.sequenceNumber ?? ''} · ${dayMonth(item.statement?.periodStart)} a ${dayMonth(item.statement?.periodEnd)}`;
    default:
      return `Pagamento informado de ${dayMonth(item.payment?.paidOn)} recusado`;
  }
}

/** O que fazer e até quando. */
export function awaitingAction(item: CreditorAwaitingItem): string {
  if (item.kind === 'PAYMENT_REJECTED') {
    return 'Confira e informe de novo';
  }
  const verb = item.kind === 'DISPUTE' ? 'Responder' : 'Aceitar ou contestar';
  if (!item.dueOn) {
    return verb;
  }
  return item.overdue ? `Prazo vencido em ${dayMonth(item.dueOn)}` : `${verb} até ${dayMonth(item.dueOn)}`;
}

/** Motivo da recusa do pagamento, como o credor vê na lista de pagamentos. */
export function awaitingReason(item: CreditorAwaitingItem): string | null {
  const reason = item.kind === 'PAYMENT_REJECTED' ? item.payment?.rejectionReason : null;
  return reason ? CREDITOR_REJECTION_REASON_LABELS[reason] : null;
}

/**
 * Cada item leva à ficha (contestação, demonstrativo); o pagamento recusado leva à lista de pagamentos, com o próprio
 * pagamento destacado ({@link awaitingQuery}).
 */
export function awaitingLink(item: CreditorAwaitingItem, slug: string): unknown[] {
  const base = ['/credor', slug];
  switch (item.kind) {
    case 'DISPUTE':
      return [...base, 'contestacoes', item.id];
    case 'STATEMENT':
      return [...base, 'prestacao-de-contas', item.id];
    default:
      return [...base, 'pagamentos'];
  }
}

/** Parâmetro da lista de pagamentos que destaca o pagamento (`?pagamento=<id>`); nulo nos itens com ficha própria. */
export const PAYMENT_PARAM = 'pagamento';

export function awaitingQuery(item: CreditorAwaitingItem): Record<string, string> | null {
  return item.kind === 'PAYMENT_REJECTED' ? { [PAYMENT_PARAM]: item.id } : null;
}

/** Prazo de resposta do demonstrativo, como aparece nas listas e na ficha do portal. */
export function statementDeadlineText(dueOn: string | null | undefined, overdue: boolean): string | null {
  if (!dueOn) {
    return null;
  }
  return overdue ? `Prazo vencido em ${dayMonth(dueOn)}` : `Aceitar ou contestar até ${dayMonth(dueOn)}`;
}
