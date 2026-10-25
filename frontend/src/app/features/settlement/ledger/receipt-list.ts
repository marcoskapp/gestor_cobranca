import { Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { debounceTime, finalize, map, startWith } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { NotificationService } from '../../../core/notification/notification.service';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { pagedQuery } from '../../../shared/data/paged-query';
import { sameValue } from '../../../shared/data/same-value';
import { isoDate } from '../../../shared/models/dates';
import { CreditorLookup } from '../../organization/data/organization.lookups';
import { ReceiptTable } from '../components/receipt-table';
import { CommissionLedgerApi } from '../data/settlement.api';
import { RatingStatus, ReceiptFilter } from '../data/settlement.models';
import { Icon } from '../../../shared/components/icon/icon';
import { FORM_FIELD } from '../../../shared/forms/field';
import { NgbInputDatepicker, NgbTooltip } from '@ng-bootstrap/ng-bootstrap';

/**
 * Razão de comissão: recebimentos e estornos apurados (append-only), com regra, versão e memória de
 * cálculo; os aguardando regra podem ser reapurados por quem gerencia comissão.
 */
@Component({
  selector: 'app-receipt-list',
  imports: [ReactiveFormsModule, NgbInputDatepicker, NgbTooltip, ...FORM_FIELD, Icon, EntityPicker, PageHeader, ReceiptTable],
  template: `
    <app-page-header title="Razão de comissão" subtitle="Cada recebimento confirmado vira um lançamento com a regra vigente na data do pagamento. Lançamentos não mudam: estornos compensam.">
      @if (canManage()) {
        <button class="btn btn-outline-primary" type="button" (click)="ratePending()" [disabled]="!creditorId() || rating()" [ngbTooltip]="creditorId() ? 'Tenta apurar os recebimentos que aguardam regra' : 'Escolha um credor'" data-tour="ledger-rate">
          <app-icon name="arrow-repeat" />
          Reapurar pendentes
        </button>
      }
    </app-page-header>
    <form class="app-filters" data-tour="filters" [formGroup]="filters" role="search" aria-label="Filtrar razão de comissão">
      <div class="app-filters__wide">
        <app-entity-picker [control]="filters.controls.creditor" [source]="creditorSource" label="Credor" [single]="true" />
      </div>
      <app-field label="Apuração">
        <select appInput formControlName="rating">
          <option value="">Todas</option>
          <option value="RATED">Apurados</option>
          <option value="PENDING_RULES">Aguardando regra</option>
        </select>
      </app-field>
      <app-field label="Prestação de contas">
        <select appInput formControlName="inStatement">
          <option value="">Todos</option>
          <option value="false">Ainda não prestados</option>
          <option value="true">Já prestados</option>
        </select>
      </app-field>
      <app-field label="Pago de">
        <div class="input-group">
          <input appInput ngbDatepicker #from="ngbDatepicker" formControlName="from" placeholder="dd/mm/aaaa" autocomplete="off" />
          <button type="button" class="btn btn-outline-secondary" aria-label="Abrir calendário (pago de)" (click)="from.toggle()">
            <app-icon name="calendar-event" />
          </button>
        </div>
      </app-field>
      <app-field label="Pago até">
        <div class="input-group">
          <input appInput ngbDatepicker #to="ngbDatepicker" formControlName="to" placeholder="dd/mm/aaaa" autocomplete="off" />
          <button type="button" class="btn btn-outline-secondary" aria-label="Abrir calendário (pago até)" (click)="to.toggle()">
            <app-icon name="calendar-event" />
          </button>
        </div>
      </app-field>
      <div class="app-filters__actions">
        <button class="btn btn-link" type="button" (click)="clear()">Limpar filtros</button>
      </div>
    </form>
    <app-receipt-table [query]="query" />
  `,
})
export class ReceiptList {
  private readonly api = inject(CommissionLedgerApi);
  private readonly auth = inject(AuthService);
  private readonly notifications = inject(NotificationService);
  protected readonly creditorSource = inject(CreditorLookup);
  protected readonly canManage = computed(() => this.auth.hasPermission(Permission.COMMISSION_MANAGE));
  protected readonly rating = signal(false);

  private readonly params = inject(ActivatedRoute).snapshot.queryParamMap;
  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly filters = this.fb.group({
    creditor: this.fb.control<string[]>(this.params.get('credor') ? [this.params.get('credor')!] : []),
    rating: [this.params.get('apuracao') ?? ''],
    inStatement: [''],
    from: this.fb.control<string | null>(null),
    to: this.fb.control<string | null>(null),
  });
  private readonly filter = toSignal(
    this.filters.valueChanges.pipe(
      startWith(null),
      debounceTime(250),
      map(() => this.current()),
    ),
    { initialValue: this.current(), equal: sameValue },
  );
  protected readonly creditorId = computed(() => this.filter().creditorId);
  protected readonly query = pagedQuery(this.filter, (f, page) => this.api.search(f, page));

  protected clear(): void {
    this.filters.reset({ creditor: [], rating: '', inStatement: '', from: null, to: null });
  }

  protected ratePending(): void {
    const id = this.creditorId();
    if (!id) {
      return;
    }
    this.rating.set(true);
    this.api
      .ratePending(id)
      .pipe(finalize(() => this.rating.set(false)))
      .subscribe((r) => {
        this.notifications.success(r.rated ? `${r.rated} recebimento(s) apurado(s).` : 'Nenhum recebimento pendente pôde ser apurado: publique a regra vigente na data do pagamento.');
        this.query.reload();
      });
  }

  private current(): ReceiptFilter {
    const v = this.filters.getRawValue();
    return {
      creditorId: v.creditor[0] ?? null,
      ratingStatus: (v.rating || null) as RatingStatus | null,
      inStatement: v.inStatement === '' ? null : v.inStatement === 'true',
      from: isoDate(v.from),
      to: isoDate(v.to),
    };
  }
}
