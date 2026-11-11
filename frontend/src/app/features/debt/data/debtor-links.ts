import { ResourceRef, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { Observable, catchError, forkJoin, map, of } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { RelatedRecords } from '../../../shared/components/related-records/related-records';
import { RelatedKind } from '../../../shared/components/related-records/related-targets';
import { CasesApi, DisputesApi } from '../../collection/data/collection.api';
import { CaseView, DisputeView } from '../../collection/data/collection.models';
import { ConversationsApi } from '../../communication/data/communication.api';
import { ConversationView } from '../../communication/data/communication.models';
import { LegalMattersApi } from '../../legal/data/legal.api';
import { MatterView } from '../../legal/data/legal.models';
import { AgreementsApi } from '../../negotiation/data/agreement.api';
import { AgreementView } from '../../negotiation/data/agreement.models';
import { PaymentsApi } from '../../payment/data/payment.api';
import { PaymentView } from '../../payment/data/payment.models';
import { DataSubjectRequestsApi } from '../../compliance/data/compliance.api';
import { DataSubjectRequestView } from '../../compliance/data/compliance.models';

/** Quantos registros de cada tipo o cartão mostra antes do "Ver todos". */
export const RELATED_PREVIEW = 5;

/** Vínculos de um devedor nos outros módulos (cada lista só é buscada se o perfil abre o destino). */
export interface DebtorLinks {
  cases: CaseView[];
  agreements: AgreementView[];
  agreementTotal: number;
  payments: PaymentView[];
  paymentTotal: number;
  disputes: DisputeView[];
  matters: MatterView[];
  conversations: ConversationView[];
  /** Solicitações de titular (LGPD) do devedor, as mais recentes primeiro. */
  dataSubjectRequests: DataSubjectRequestView[];
  dataSubjectRequestTotal: number;
}

export const NO_LINKS: DebtorLinks = {
  cases: [],
  agreements: [],
  agreementTotal: 0,
  payments: [],
  paymentTotal: 0,
  disputes: [],
  matters: [],
  conversations: [],
  dataSubjectRequests: [],
  dataSubjectRequestTotal: 0,
};

/** Quais vínculos buscar (o título só precisa de parte deles). */
export type DebtorLinkKind = 'cases' | 'agreements' | 'payments' | 'disputes' | 'matters' | 'conversations' | 'dataSubjectRequests';

const PAGE = { page: 0, size: RELATED_PREVIEW };
const MANY = { page: 0, size: 50 };

/**
 * Carrega os vínculos do devedor para o cartão "Relacionados" e as ações da ficha, pelos endpoints que cada módulo já
 * expõe (escopo por credor no servidor). Só consulta o que o perfil pode abrir — sem 403 nem "acesso negado" no log —
 * e uma falha num módulo esvazia só aquele grupo.
 *
 * Deve ser chamado num contexto de injeção (campo do componente).
 */
export function debtorLinks(debtorId: () => string | null | undefined, kinds: readonly DebtorLinkKind[]): ResourceRef<DebtorLinks | undefined> {
  const auth = inject(AuthService);
  const cases = inject(CasesApi);
  const disputes = inject(DisputesApi);
  const agreements = inject(AgreementsApi);
  const payments = inject(PaymentsApi);
  const matters = inject(LegalMattersApi);
  const conversations = inject(ConversationsApi);
  const dataSubjectRequests = inject(DataSubjectRequestsApi);
  const has = (p: string) => auth.hasPermission(p);
  const opens = (kind: RelatedKind) => RelatedRecords.canOpen(kind, has);
  const wants = (kind: DebtorLinkKind) => kinds.includes(kind);
  const safe = <T>(source: Observable<T>, fallback: T) => source.pipe(catchError(() => of(fallback)));

  return rxResource({
    params: () => debtorId() ?? undefined,
    stream: ({ params: id }) =>
      // uma chamada por módulo (nenhuma por caso): casos, acordos, pagamentos, contestações, jurídico e conversas
      forkJoin({
        cases: wants('cases') && has(Permission.CASE_VIEW) ? safe(cases.byDebtor(id), [] as CaseView[]) : of([] as CaseView[]),
        agreements: wants('agreements') && opens('agreement')
          ? safe(agreements.search({ creditorId: null, debtorId: id, caseId: null, status: null }, wants('payments') ? PAGE : MANY), null)
          : of(null),
        payments: wants('payments') && opens('payment')
          ? safe(payments.search({ creditorId: null, debtorId: id, status: null, source: null, method: null, paidFrom: null, paidTo: null, withBalance: null }, PAGE), null)
          : of(null),
        // contestações do devedor (filtro `debtorId` da fila; a lista exige CASE_VIEW ou DISPUTE_*, o destino DISPUTE_*)
        disputes: wants('disputes') && opens('dispute') ? safe(disputes.search({ debtorId: id }, PAGE), null) : of(null),
        matters: wants('matters') && opens('legalMatter') ? safe(matters.search({ debtorId: id }, MANY), null) : of(null),
        conversations: wants('conversations') && opens('conversation')
          ? safe(conversations.inbox({ debtorId: id, status: 'ALL' }, PAGE), null)
          : of(null),
        // pedidos do titular (LGPD): basta o devedor visível no servidor; só para quem atende titulares
        dataSubjectRequests: wants('dataSubjectRequests') && opens('dataSubjectRequest')
          ? safe(dataSubjectRequests.search({ debtorId: id }, PAGE), null)
          : of(null),
      }).pipe(
        map(
          (r): DebtorLinks => ({
            cases: r.cases,
            agreements: r.agreements?.content ?? [],
            agreementTotal: r.agreements?.totalElements ?? 0,
            payments: r.payments?.content ?? [],
            paymentTotal: r.payments?.totalElements ?? 0,
            disputes: r.disputes?.content ?? [],
            matters: r.matters?.content ?? [],
            conversations: r.conversations?.content ?? [],
            dataSubjectRequests: r.dataSubjectRequests?.content ?? [],
            dataSubjectRequestTotal: r.dataSubjectRequests?.totalElements ?? 0,
          }),
        ),
      ),
  });
}

/** Casos ativos (não encerrados) — destino de "Abrir caso". */
export function activeCases(cases: readonly CaseView[]): CaseView[] {
  return cases.filter((c) => c.status !== 'CLOSED');
}

