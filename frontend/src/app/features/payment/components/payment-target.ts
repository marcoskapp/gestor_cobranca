import { Component, computed, effect, inject, input, model, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { catchError, map, of } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { Amount } from '../../../shared/components/amount/amount';
import { nameMap } from '../../../shared/data/name-map';
import { valueOr } from '../../../shared/data/resource-value';
import { FORM_FIELD } from '../../../shared/forms/field';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { TitlesApi } from '../../debt/data/debt.api';
import { TitleView } from '../../debt/data/debt.models';
import { AgreementsApi } from '../../negotiation/data/agreement.api';
import { AgreementView, InstallmentView } from '../../negotiation/data/agreement.models';
import { installmentBalance } from '../../negotiation/data/agreement.rules';
import { CreditorNames } from '../../organization/data/organization.lookups';
import { STRATEGY_LABELS } from '../data/payment.labels';
import { TitleStrategy } from '../data/payment.models';

/** Destino escolhido: uma parcela/um título (instrução) ou parcelas de um acordo/títulos (alocação). */
export type TargetSelection =
  | { kind: 'INSTALLMENT'; creditorId: string; agreementId: string; installmentId: string; installmentNumber: number; dueDate: string; balance: number }
  | { kind: 'TITLE'; creditorId: string; titleId: string; label: string; dueDate: string; balance: number }
  | { kind: 'AGREEMENT'; creditorId: string; agreementId: string; itemIds: string[]; balance: number }
  | { kind: 'TITLES'; creditorId: string; itemIds: string[]; strategy: TitleStrategy; balance: number };

const ACTIVE_AGREEMENTS = ['FORMALIZED', 'IN_PAYMENT'];

let nextId = 0;

interface Installment extends InstallmentView {
  balance: number;
}

/**
 * Escolha do destino do dinheiro entre os acordos em vigor e os títulos em aberto do devedor.
 * Em `single` (instrução de pagamento) escolhe-se uma parcela ou um título; em `allocation`
 * (pagamento informado, realocação) parcelas de um acordo — vazio = todas em aberto, na ordem —
 * ou títulos avulsos com a regra de distribuição. Títulos cobertos por acordo em vigor ficam de
 * fora: pagam-se pela parcela.
 */
@Component({
  selector: 'app-payment-target',
  imports: [FormsModule, ...FORM_FIELD, Amount, AppDatePipe],
  template: `
    <div class="d-flex flex-column gap-2 mb-2" data-tour="payment-target">
      <div class="btn-group align-self-start" role="group" aria-label="Tipo de destino">
        <input type="radio" class="btn-check" [name]="uid + '-kind'" [id]="uid + '-kind-a'" [checked]="kind() === 'AGREEMENT'" (change)="setKind('AGREEMENT')" />
        <label class="btn btn-outline-primary" [for]="uid + '-kind-a'">Parcela de acordo</label>
        <input type="radio" class="btn-check" [name]="uid + '-kind'" [id]="uid + '-kind-t'" [checked]="kind() === 'TITLES'" (change)="setKind('TITLES')" />
        <label class="btn btn-outline-primary" [for]="uid + '-kind-t'">{{ mode() === 'single' ? 'Título avulso' : 'Títulos avulsos' }}</label>
      </div>

      @if (kind() === 'AGREEMENT') {
        @if (!canReadAgreements()) {
          <p class="app-muted mb-0">Seu perfil não consulta acordos.</p>
        } @else if (agreements.isLoading()) {
          <p class="app-muted mb-0" role="status">Carregando acordos…</p>
        } @else if (!activeAgreements().length) {
          <p class="app-muted mb-0">Nenhum acordo em vigor para este devedor.</p>
        } @else {
          @for (a of activeAgreements(); track a.id) {
            <fieldset class="border rounded px-3 py-2 m-0">
              <legend class="float-none w-auto px-1 mb-1 fs-6 fw-medium">
                @if (mode() === 'allocation') {
                  <span class="form-check d-inline-block mb-0">
                    <input class="form-check-input" type="radio" [name]="uid + '-agreement'" [id]="uid + '-ag-' + a.id" [checked]="agreementId() === a.id" (change)="pickAgreement(a)" />
                    <label class="form-check-label" [for]="uid + '-ag-' + a.id">Acordo de {{ a.formalizedAt | appDate: 'date' }} · {{ creditors.name(a.creditorId) }}</label>
                  </span>
                } @else {
                  Acordo de {{ a.formalizedAt | appDate: 'date' }} · {{ creditors.name(a.creditorId) }}
                }
              </legend>
              <ul class="list-unstyled m-0 overflow-auto pt__list">
                @for (i of openInstallments(a); track i.id) {
                  <li class="form-check">
                    @if (mode() === 'single') {
                      <input class="form-check-input" type="radio" [name]="uid + '-item'" [id]="uid + '-i-' + i.id" [checked]="installmentId() === i.id" (change)="pickInstallment(a, i)" />
                    } @else {
                      <input class="form-check-input" type="checkbox" [id]="uid + '-i-' + i.id" [checked]="itemIds().includes(i.id)" [disabled]="agreementId() !== a.id" (change)="toggleItem(i.id)" />
                    }
                    <label class="form-check-label d-inline-flex flex-wrap column-gap-3 small" [for]="uid + '-i-' + i.id">
                      <span>{{ i.number }}ª parcela · vence {{ i.dueDate | appDate: 'date' }}</span>
                      <app-amount [value]="i.balance" />
                    </label>
                  </li>
                } @empty {
                  <li class="app-muted">Sem parcelas em aberto.</li>
                }
              </ul>
              @if (mode() === 'allocation' && agreementId() === a.id) {
                <p class="form-text mt-1 mb-0">{{ itemIds().length ? 'As parcelas marcadas recebem primeiro; o excedente vai às seguintes.' : 'Sem parcelas marcadas: o valor vai às parcelas em aberto, pela ordem.' }}</p>
              }
            </fieldset>
          }
        }
      } @else {
        @if (titles.isLoading()) {
          <p class="app-muted mb-0" role="status">Carregando títulos…</p>
        } @else if (!payableTitles().length) {
          <p class="app-muted mb-0">Nenhum título em aberto fora de acordo para este devedor.</p>
        } @else {
          <ul class="list-unstyled m-0 overflow-auto pt__list">
            @for (t of payableTitles(); track t.id) {
              <li class="form-check">
                @if (mode() === 'single') {
                  <input class="form-check-input" type="radio" [name]="uid + '-item'" [id]="uid + '-t-' + t.id" [checked]="titleId() === t.id" (change)="pickTitle(t)" />
                } @else {
                  <input
                    class="form-check-input"
                    type="checkbox"
                    [id]="uid + '-t-' + t.id"
                    [checked]="itemIds().includes(t.id)"
                    [disabled]="!!titleCreditor() && titleCreditor() !== t.creditorId"
                    (change)="toggleTitle(t)"
                  />
                }
                <label class="form-check-label d-inline-flex flex-wrap column-gap-3 small" [for]="uid + '-t-' + t.id">
                  <span>{{ titleLabel(t) }} · vence {{ t.dueDate | appDate: 'date' }} · {{ creditors.name(t.creditorId) }}</span>
                  <app-amount [value]="t.openBalance" />
                </label>
              </li>
            }
          </ul>
          @if (mode() === 'allocation') {
            <app-field class="pt__strategy" label="Distribuição entre os títulos" hint="Vencidos primeiro segue o Código Civil (art. 355).">
              <select appInput [ngModel]="strategy()" (ngModelChange)="setStrategy($event)">
                @for (s of strategies; track s[0]) {
                  <option [ngValue]="s[0]">{{ s[1] }}</option>
                }
              </select>
            </app-field>
          }
        }
      }
    </div>
  `,
  styles: `
    .pt__list {
      max-height: 240px;
    }
    .pt__strategy {
      max-width: 320px;
    }
  `,
})
export class PaymentTarget {
  protected readonly uid = `app-pt-${++nextId}`;
  readonly debtorId = input.required<string>();
  /** Restringe ao credor (pagamento já existente: realocação só dentro do mesmo credor). */
  readonly creditorId = input<string | null>(null);
  readonly mode = input<'single' | 'allocation'>('single');
  /** Kind inicial ao abrir. */
  readonly initialKind = input<'AGREEMENT' | 'TITLES'>('AGREEMENT');
  readonly selection = model<TargetSelection | null>(null);

  private readonly auth = inject(AuthService);
  private readonly agreementsApi = inject(AgreementsApi);
  private readonly titlesApi = inject(TitlesApi);
  protected readonly strategies = Object.entries(STRATEGY_LABELS) as [TitleStrategy, string][];

  protected readonly kind = signal<'AGREEMENT' | 'TITLES'>('AGREEMENT');
  protected readonly agreementId = signal<string | null>(null);
  protected readonly installmentId = signal<string | null>(null);
  protected readonly titleId = signal<string | null>(null);
  protected readonly itemIds = signal<string[]>([]);
  protected readonly strategy = signal<TitleStrategy>('OLDEST_FIRST');
  protected readonly canReadAgreements = computed(() => this.auth.hasPermission(Permission.AGREEMENT_VIEW));

  protected readonly agreements = rxResource({
    params: () => (this.canReadAgreements() ? { debtorId: this.debtorId(), creditorId: this.creditorId() } : undefined),
    stream: ({ params }) =>
      this.agreementsApi.search({ creditorId: params.creditorId, debtorId: params.debtorId, caseId: null, status: null }, { page: 0, size: 50 }).pipe(
        map((page) => page.content),
        catchError(() => of<AgreementView[]>([])),
      ),
  });
  private readonly agreementList = valueOr(this.agreements, []);
  protected readonly activeAgreements = computed(() => this.agreementList().filter((a) => ACTIVE_AGREEMENTS.includes(a.status)));

  protected readonly titles = rxResource({
    params: () => ({ debtorId: this.debtorId(), creditorId: this.creditorId() }),
    stream: ({ params }) =>
      this.titlesApi.search({ debtorId: params.debtorId, creditorId: params.creditorId, status: 'OPEN' }, { page: 0, size: 100 }).pipe(
        map((page) => page.content),
        catchError(() => of<TitleView[]>([])),
      ),
  });
  private readonly titleList = valueOr(this.titles, []);
  /** Títulos em aberto com saldo e fora de acordo em vigor. */
  protected readonly payableTitles = computed(() => {
    const covered = new Set(this.activeAgreements().flatMap((a) => a.titleIds));
    return this.titleList().filter((t) => t.openBalance > 0 && !covered.has(t.id));
  });
  protected readonly titleCreditor = computed(() => {
    const first = this.payableTitles().find((t) => this.itemIds().includes(t.id));
    return first?.creditorId ?? null;
  });
  protected readonly creditors = nameMap(inject(CreditorNames), () => [...this.activeAgreements().map((a) => a.creditorId), ...this.payableTitles().map((t) => t.creditorId)]);

  constructor() {
    effect(() => this.kind.set(this.initialKind()));
    // em alocação com um único acordo em vigor, já o escolhe
    effect(() => {
      const list = this.activeAgreements();
      if (this.mode() === 'allocation' && this.kind() === 'AGREEMENT' && list.length === 1 && !this.agreementId()) {
        this.pickAgreement(list[0]);
      }
    });
  }

  protected openInstallments(a: AgreementView): Installment[] {
    return [...a.installments]
      .sort((x, y) => x.number - y.number)
      .map((i) => ({ ...i, balance: installmentBalance(i) }))
      .filter((i) => i.status !== 'PAID' && i.status !== 'CANCELLED' && i.balance > 0);
  }

  protected titleLabel(t: TitleView): string {
    return `Título ${t.externalId}${t.installmentNumber ? '/' + t.installmentNumber : ''}`;
  }

  protected setKind(kind: 'AGREEMENT' | 'TITLES'): void {
    this.kind.set(kind);
    this.reset();
  }

  protected pickInstallment(a: AgreementView, i: Installment): void {
    this.agreementId.set(a.id);
    this.installmentId.set(i.id);
    this.selection.set({ kind: 'INSTALLMENT', creditorId: a.creditorId, agreementId: a.id, installmentId: i.id, installmentNumber: i.number, dueDate: i.dueDate, balance: i.balance });
  }

  protected pickTitle(t: TitleView): void {
    this.titleId.set(t.id);
    this.selection.set({ kind: 'TITLE', creditorId: t.creditorId, titleId: t.id, label: this.titleLabel(t), dueDate: t.dueDate, balance: t.openBalance });
  }

  protected pickAgreement(a: AgreementView): void {
    this.agreementId.set(a.id);
    this.itemIds.set([]);
    this.emitAgreement(a);
  }

  protected toggleItem(id: string): void {
    this.itemIds.update((list) => (list.includes(id) ? list.filter((x) => x !== id) : [...list, id]));
    const a = this.activeAgreements().find((x) => x.id === this.agreementId());
    if (a) {
      this.emitAgreement(a);
    }
  }

  protected toggleTitle(t: TitleView): void {
    this.itemIds.update((list) => (list.includes(t.id) ? list.filter((x) => x !== t.id) : [...list, t.id]));
    this.emitTitles();
  }

  protected setStrategy(s: TitleStrategy): void {
    this.strategy.set(s);
    this.emitTitles();
  }

  private emitAgreement(a: AgreementView): void {
    const open = this.openInstallments(a);
    const chosen = this.itemIds().length ? open.filter((i) => this.itemIds().includes(i.id)) : open;
    // o saldo do acordo inteiro: parcelas marcadas recebem primeiro, o excedente vai às seguintes
    const balance = open.reduce((sum, i) => sum + i.balance, 0);
    this.selection.set({ kind: 'AGREEMENT', creditorId: a.creditorId, agreementId: a.id, itemIds: chosen.length === open.length ? [] : chosen.map((i) => i.id), balance: Math.round(balance * 100) / 100 });
  }

  private emitTitles(): void {
    const chosen = this.payableTitles().filter((t) => this.itemIds().includes(t.id));
    if (!chosen.length) {
      this.selection.set(null);
      return;
    }
    const balance = chosen.reduce((sum, t) => sum + t.openBalance, 0);
    this.selection.set({ kind: 'TITLES', creditorId: chosen[0].creditorId, itemIds: chosen.map((t) => t.id), strategy: this.strategy(), balance: Math.round(balance * 100) / 100 });
  }

  private reset(): void {
    this.agreementId.set(null);
    this.installmentId.set(null);
    this.titleId.set(null);
    this.itemIds.set([]);
    this.selection.set(null);
  }
}
