import { Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { startWith } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { FORM_FIELD } from '../../../shared/forms/field';
import { UserLookup } from '../../admin/data/user.lookup';
import { zonedNow } from '../../collection/data/collection.rules';
import { LedgerTable } from '../components/ledger-table';
import { monthOptions } from '../data/incentive.rules';

/**
 * Razão de comissão do negociador (padrão: o próprio): cada pagamento confirmado de acordo com
 * crédito, o peso aplicado, a carência e o período em que entra.
 */
@Component({
  selector: 'app-incentive-ledger-list',
  imports: [ReactiveFormsModule, ...FORM_FIELD, EntityPicker, LedgerTable, PageHeader],
  template: `
    <app-page-header title="Lançamentos de comissão" subtitle="Pagamentos creditados ao negociador, com peso de crédito, carência e período de competência." />
    <form class="app-filters" data-tour="filters" [formGroup]="form" role="search" aria-label="Filtrar lançamentos">
      <app-field label="Período de competência">
        <select appInput formControlName="period">
          <option value="">Todos</option>
          @for (m of months; track m.value) {
            <option [ngValue]="m.value">{{ m.label }}</option>
          }
        </select>
      </app-field>
      @if (canPickPeople()) {
        <div class="app-filters__wide">
          <app-entity-picker [control]="form.controls.user" [source]="users" label="Negociador" hint="Vazio = os seus lançamentos" [single]="true" />
        </div>
      }
    </form>
    <app-ledger-table [userId]="userId()" [period]="period()" />
  `,
})
export class LedgerList {
  private readonly auth = inject(AuthService);
  protected readonly users = inject(UserLookup);
  protected readonly months = monthOptions(zonedNow().date, 24);
  protected readonly canPickPeople = computed(
    () =>
      this.auth.hasPermission(Permission.USER_VIEW) &&
      this.auth.hasAnyPermission([Permission.PERFORMANCE_VIEW_TEAM, Permission.PERFORMANCE_VIEW_ALL, Permission.COMMISSION_STATEMENT_MANAGE, Permission.COMMISSION_STATEMENT_APPROVE]),
  );
  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({ period: [''], user: this.fb.control<string[]>([]) });
  private readonly value = toSignal(this.form.valueChanges.pipe(startWith(null)), { initialValue: null });
  protected readonly period = computed(() => {
    this.value();
    return this.form.getRawValue().period || null;
  });
  protected readonly userId = computed(() => {
    this.value();
    return this.form.getRawValue().user[0] ?? null;
  });
}
