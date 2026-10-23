import { Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MAT_DIALOG_DATA, MatDialog, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatTooltipModule } from '@angular/material/tooltip';
import { finalize } from 'rxjs';
import { toApiError } from '../../../core/http/problem';
import { NotificationService } from '../../../core/notification/notification.service';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { CellDef } from '../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../shared/components/data-table/data-table';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { valueOr } from '../../../shared/data/resource-value';
import { applyServerErrors } from '../../../shared/validation/server-errors';
import { AppValidators } from '../../../shared/validation/validators';
import { TabulationsApi } from '../data/collection.api';
import { TabulationRequest, TabulationView } from '../data/collection.models';

/** Efeitos da tabulação em texto (lista e formulário). */
export function tabulationEffects(t: Pick<TabulationView, 'effectiveContact' | 'promise' | 'callback' | 'requiresNote'>): string[] {
  return [
    t.effectiveContact ? 'Contato efetivo (leva o caso a "Em negociação")' : null,
    t.promise ? 'Exige promessa (valor e data)' : null,
    t.callback ? 'Exige retorno (data e hora)' : null,
    t.requiresNote ? 'Observação obrigatória' : null,
  ].filter((e): e is string => !!e);
}

/** Cadastro de tabulação. Promessa e retorno são exclusivos; promessa pressupõe contato efetivo. */
@Component({
  selector: 'app-tabulation-form',
  imports: [ReactiveFormsModule, MatDialogModule, MatButtonModule, MatCheckboxModule, MatFormFieldModule, MatInputModule],
  template: `
    <h2 mat-dialog-title>{{ data ? 'Editar tabulação' : 'Nova tabulação' }}</h2>
    <form [formGroup]="form" (ngSubmit)="save()" novalidate>
      <mat-dialog-content>
        @for (e of errors(); track e) {
          <p class="app-alert app-alert--error" role="alert">{{ e }}</p>
        }
        <div class="app-form-grid">
          <mat-form-field>
            <mat-label>Nome</mat-label>
            <input matInput formControlName="name" maxlength="120" required />
          </mat-form-field>
          <mat-form-field>
            <mat-label>Código</mat-label>
            <input matInput formControlName="code" maxlength="40" [readonly]="!!data" />
            <mat-hint>{{ data ? 'Imutável (usado nos relatórios).' : 'Vazio = gerado pelo nome.' }}</mat-hint>
          </mat-form-field>
          <mat-form-field>
            <mat-label>Ordem de exibição</mat-label>
            <input matInput type="number" formControlName="displayOrder" />
          </mat-form-field>
        </div>
        <div class="tf-flags" role="group" aria-label="Efeitos da tabulação">
          <mat-checkbox formControlName="effectiveContact">Contato efetivo (CPC)</mat-checkbox>
          <mat-checkbox formControlName="promise">Registra promessa de pagamento</mat-checkbox>
          <mat-checkbox formControlName="callback">Agenda retorno</mat-checkbox>
          <mat-checkbox formControlName="requiresNote">Observação obrigatória</mat-checkbox>
        </div>
        @if (conflict()) {
          <p class="app-field-error" role="alert">{{ conflict() }}</p>
        }
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button mat-button type="button" mat-dialog-close>Cancelar</button>
        <button mat-flat-button type="submit" [disabled]="saving()">Salvar</button>
      </mat-dialog-actions>
    </form>
  `,
  styles: `
    .tf-flags {
      display: flex;
      flex-direction: column;
    }
  `,
})
export class TabulationForm {
  protected readonly data = inject<TabulationView | null>(MAT_DIALOG_DATA);
  private readonly ref = inject<MatDialogRef<TabulationForm, TabulationView>>(MatDialogRef);
  private readonly api = inject(TabulationsApi);
  private readonly notifications = inject(NotificationService);
  protected readonly saving = signal(false);
  protected readonly errors = signal<string[]>([]);
  protected readonly conflict = signal<string | null>(null);

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    code: [this.data?.code ?? '', [Validators.maxLength(40)]],
    name: [this.data?.name ?? '', [AppValidators.notBlank, Validators.maxLength(120)]],
    displayOrder: [this.data?.displayOrder ?? 100],
    effectiveContact: [this.data?.effectiveContact ?? false],
    promise: [this.data?.promise ?? false],
    callback: [this.data?.callback ?? false],
    requiresNote: [this.data?.requiresNote ?? false],
  });

  constructor() {
    // promessa pressupõe contato efetivo; promessa e retorno são exclusivos
    this.form.controls.promise.valueChanges.subscribe((on) => {
      if (on) {
        this.form.patchValue({ effectiveContact: true, callback: false }, { emitEvent: false });
      }
    });
    this.form.controls.callback.valueChanges.subscribe((on) => on && this.form.controls.promise.setValue(false, { emitEvent: false }));
  }

  protected save(): void {
    const v = this.form.getRawValue();
    this.conflict.set(v.promise && !v.effectiveContact ? 'Promessa exige contato efetivo.' : v.promise && v.callback ? 'Promessa e retorno são exclusivos.' : null);
    if (this.form.invalid || this.conflict() || this.saving()) {
      this.form.markAllAsTouched();
      return;
    }
    const request: TabulationRequest = { ...v, code: this.data ? null : v.code.trim() || null, name: v.name.trim(), displayOrder: Number(v.displayOrder) || 0 };
    this.saving.set(true);
    (this.data ? this.api.update(this.data.id, request) : this.api.create(request))
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe({
        next: (saved) => {
          this.notifications.success('Tabulação salva.');
          this.ref.close(saved);
        },
        error: (err: unknown) => this.errors.set(applyServerErrors(this.form, toApiError(err))),
      });
  }
}

/** Tabulações (resultados de interação) da assessoria: desativadas, nunca removidas. */
@Component({
  selector: 'app-tabulation-list',
  imports: [MatButtonModule, MatIconModule, MatTooltipModule, CellDef, DataTable, PageHeader, StatusChip],
  template: `
    <app-page-header title="Tabulações" subtitle="Resultados de contato usados pelos operadores. Os efeitos definem o que o registro exige e como o caso avança.">
      <button mat-flat-button type="button" (click)="edit(null)">
        <mat-icon aria-hidden="true">add</mat-icon>
        Nova tabulação
      </button>
    </app-page-header>
    <app-data-table
      caption="Tabulações"
      [rows]="rows()"
      [columns]="columns"
      [loading]="list.isLoading()"
      [failed]="list.status() === 'error'"
      (retry)="list.reload()"
      emptyIcon="label"
      emptyMessage="Nenhuma tabulação cadastrada."
    >
      <ng-template appCell="order" [appCellOf]="rows()" let-t><span class="app-numeric">{{ t.displayOrder }}</span></ng-template>
      <ng-template appCell="name" [appCellOf]="rows()" let-t>
        <strong>{{ t.name }}</strong>&ngsp;<span class="app-muted app-mono">{{ t.code }}</span>
      </ng-template>
      <ng-template appCell="effects" [appCellOf]="rows()" let-t>{{ effects(t).join(' · ') || 'Sem efeitos (registro simples)' }}</ng-template>
      <ng-template appCell="active" [appCellOf]="rows()" let-t><app-status-chip domain="active" [status]="t.active" /></ng-template>
      <ng-template appCell="actions" [appCellOf]="rows()" let-t>
        <button mat-icon-button type="button" (click)="edit(t)" [attr.aria-label]="'Editar ' + t.name" matTooltip="Editar">
          <mat-icon aria-hidden="true">edit</mat-icon>
        </button>
        <button mat-icon-button type="button" (click)="toggle(t)" [attr.aria-label]="(t.active ? 'Desativar ' : 'Ativar ') + t.name" [matTooltip]="t.active ? 'Desativar' : 'Ativar'">
          <mat-icon aria-hidden="true">{{ t.active ? 'toggle_on' : 'toggle_off' }}</mat-icon>
        </button>
      </ng-template>
    </app-data-table>
  `,
})
export class TabulationList {
  private readonly api = inject(TabulationsApi);
  private readonly dialog = inject(MatDialog);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);

  protected readonly effects = tabulationEffects;
  protected readonly list = rxResource({ stream: () => this.api.list(false) });
  private readonly all = valueOr(this.list, []);
  protected readonly rows = computed(() => [...this.all()].sort((a, b) => a.displayOrder - b.displayOrder || a.name.localeCompare(b.name)));
  protected readonly columns: TableColumn[] = [
    { key: 'order', header: 'Ordem', align: 'end' },
    { key: 'name', header: 'Tabulação' },
    { key: 'effects', header: 'Efeitos' },
    { key: 'active', header: 'Situação' },
    { key: 'actions', header: 'Ações', actions: true },
  ];

  protected edit(t: TabulationView | null): void {
    this.dialog
      .open<TabulationForm, TabulationView | null, TabulationView>(TabulationForm, { data: t, width: '560px' })
      .afterClosed()
      .subscribe((saved) => saved && this.list.reload());
  }

  protected toggle(t: TabulationView): void {
    this.confirm
      .confirm({
        title: t.active ? 'Desativar tabulação' : 'Ativar tabulação',
        message: t.active ? 'A tabulação deixa de aparecer na estação; o histórico é preservado.' : 'A tabulação volta a aparecer para os operadores.',
        confirmLabel: t.active ? 'Desativar' : 'Ativar',
        details: [{ label: 'Tabulação', value: t.name }],
      })
      .subscribe(() =>
        this.api.setActive(t.id, !t.active).subscribe(() => {
          this.notifications.success(t.active ? 'Tabulação desativada.' : 'Tabulação ativada.');
          this.list.reload();
        }),
      );
  }
}
