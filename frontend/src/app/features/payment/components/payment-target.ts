import { Component, computed, effect, inject, input, model, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatRadioModule } from '@angular/material/radio';
import { MatSelectModule } from '@angular/material/select';
import { catchError, map, of } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { Amount } from '../../../shared/components/amount/amount';
import { nameMap } from '../../../shared/data/name-map';
import { valueOr } from '../../../shared/data/resource-value';
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
  imports: [FormsModule, MatButtonToggleModule, MatCheckboxModule, MatFormFieldModule, MatRadioModule, MatSelectModule, Amount, AppDatePipe],
  template: `
    <div class="pt" data-tour="payment-target">
      <mat-button-toggle-group [value]="kind()" (change)="setKind($event.value)" aria-label="Tipo de destino" class="pt__kind">
        <mat-button-toggle value="AGREEMENT">Parcela de acordo</mat-button-toggle>
        <mat-button-toggle value="TITLES">{{ mode() === 'single' ? 'Título avulso' : 'Títulos avulsos' }}</mat-button-toggle>
      </mat-button-toggle-group>

      @if (kind() === 'AGREEMENT') {
        @if (!canReadAgreements()) {
          <p class="app-muted">Seu perfil não consulta acordos.</p>
        } @else if (agreements.isLoading()) {
          <p class="app-muted" role="status">Carregando acordos…</p>
        } @else if (!activeAgreements().length) {
          <p class="app-muted">Nenhum acordo em vigor para este devedor.</p>
        } @else {
          @for (a of activeAgreements(); track a.id) {
            <fieldset class="pt__group">
              <legend>
                @if (mode() === 'allocation') {
                  <mat-radio-button name="pt-agreement" [checked]="agreementId() === a.id" (change)="pickAgreement(a)">
                    Acordo de {{ a.formalizedAt | appDate: 'date' }} · {{ creditors.name(a.creditorId) }}
                  </mat-radio-button>
                } @else {
                  Acordo de {{ a.formalizedAt | appDate: 'date' }} · {{ creditors.name(a.creditorId) }}
                }
              </legend>
              <ul class="pt__list">
                @for (i of openInstallments(a); track i.id) {
                  <li>
                    @if (mode() === 'single') {
                      <mat-radio-button name="pt-item" [checked]="installmentId() === i.id" (change)="pickInstallment(a, i)">
                        <span class="pt__item">
                          <span>{{ i.number }}ª parcela · vence {{ i.dueDate | appDate: 'date' }}</span>
                          <app-amount [value]="i.balance" />
                        </span>
                      </mat-radio-button>
                    } @else {
                      <mat-checkbox [checked]="itemIds().includes(i.id)" [disabled]="agreementId() !== a.id" (change)="toggleItem(i.id)">
                        <span class="pt__item">
                          <span>{{ i.number }}ª parcela · vence {{ i.dueDate | appDate: 'date' }}</span>
                          <app-amount [value]="i.balance" />
                        </span>
                      </mat-checkbox>
                    }
                  </li>
                } @empty {
                  <li class="app-muted">Sem parcelas em aberto.</li>
                }
              </ul>
              @if (mode() === 'allocation' && agreementId() === a.id) {
                <p class="app-muted pt__hint">{{ itemIds().length ? 'As parcelas marcadas recebem primeiro; o excedente vai às seguintes.' : 'Sem parcelas marcadas: o valor vai às parcelas em aberto, pela ordem.' }}</p>
              }
            </fieldset>
          }
        }
      } @else {
        @if (titles.isLoading()) {
          <p class="app-muted" role="status">Carregando títulos…</p>
        } @else if (!payableTitles().length) {
          <p class="app-muted">Nenhum título em aberto fora de acordo para este devedor.</p>
        } @else {
          <ul class="pt__list">
            @for (t of payableTitles(); track t.id) {
              <li>
                @if (mode() === 'single') {
                  <mat-radio-button name="pt-item" [checked]="titleId() === t.id" (change)="pickTitle(t)">
                    <span class="pt__item">
                      <span>{{ titleLabel(t) }} · vence {{ t.dueDate | appDate: 'date' }} · {{ creditors.name(t.creditorId) }}</span>
                      <app-amount [value]="t.openBalance" />
                    </span>
                  </mat-radio-button>
                } @else {
                  <mat-checkbox [checked]="itemIds().includes(t.id)" [disabled]="!!titleCreditor() && titleCreditor() !== t.creditorId" (change)="toggleTitle(t)">
                    <span class="pt__item">
                      <span>{{ titleLabel(t) }} · vence {{ t.dueDate | appDate: 'date' }} · {{ creditors.name(t.creditorId) }}</span>
                      <app-amount [value]="t.openBalance" />
                    </span>
                  </mat-checkbox>
                }
              </li>
            }
          </ul>
          @if (mode() === 'allocation') {
            <mat-form-field class="pt__strategy" subscriptSizing="dynamic">
              <mat-label>Distribuição entre os títulos</mat-label>
              <mat-select [ngModel]="strategy()" (ngModelChange)="setStrategy($event)">
                @for (s of strategies; track s[0]) {
                  <mat-option [value]="s[0]">{{ s[1] }}</mat-option>
                }
              </mat-select>
              <mat-hint>Vencidos primeiro segue o Código Civil (art. 355).</mat-hint>
            </mat-form-field>
          }
        }
      }
    </div>
  `,
  styles: `
    .pt {
      display: flex;
      flex-direction: column;
      gap: 8px;
    }
    .pt__kind {
      align-self: flex-start;
      flex-wrap: wrap;
    }
    .pt__group {
      margin: 0;
      padding: 8px 12px;
      border: 1px solid var(--app-border);
      border-radius: var(--app-radius-md);
      min-width: 0;
    }
    legend {
      padding: 0 4px;
      font-size: var(--app-text-sm);
      font-weight: 600;
    }
    .pt__list {
      margin: 0;
      padding: 0;
      list-style: none;
      max-height: 240px;
      overflow: auto;
    }
    .pt__item {
      display: inline-flex;
      flex-wrap: wrap;
      gap: 4px 12px;
      font-size: var(--app-text-sm);
    }
    .pt__hint {
      margin: 4px 0 0;
      font-size: var(--app-text-xs);
    }
    .pt__strategy {
      max-width: 320px;
    }
  `,
})
export class PaymentTarget {
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
