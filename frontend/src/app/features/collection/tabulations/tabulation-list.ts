import { Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
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
import { DISPUTE_TYPE_LABELS, FOLLOW_UP_LABELS } from '../data/collection.labels';
import { DisputeType, TabulationFollowUp, TabulationRequest, TabulationView } from '../data/collection.models';
import { NgbTooltip } from '@ng-bootstrap/ng-bootstrap';
import { Icon } from '../../../shared/components/icon/icon';
import { FORM_FIELD } from '../../../shared/forms/field';
import { DIALOG_DIRECTIVES } from '../../../shared/dialog/dialog.directives';
import { DIALOG_DATA, DialogRef } from '../../../shared/dialog/dialog-ref';
import { DialogService } from '../../../shared/dialog/dialog.service';

/** Efeitos da tabulação em texto (lista e formulário). */
export function tabulationEffects(
  t: Pick<TabulationView, 'effectiveContact' | 'promise' | 'callback' | 'requiresNote'> & Partial<Pick<TabulationView, 'followUp' | 'disputeType'>>,
): string[] {
  const followUp =
    t.followUp && t.followUp !== 'NONE'
      ? `Ação automática: ${FOLLOW_UP_LABELS[t.followUp].toLowerCase()}${t.followUp === 'OPEN_DISPUTE' && t.disputeType ? ` (${DISPUTE_TYPE_LABELS[t.disputeType].toLowerCase()})` : ''}`
      : null;
  return [
    t.effectiveContact ? 'Contato efetivo (leva o caso a "Em negociação")' : null,
    t.promise ? 'Exige promessa (valor e data)' : null,
    t.callback ? 'Exige retorno (data e hora)' : null,
    t.requiresNote ? 'Observação obrigatória' : null,
    followUp,
  ].filter((e): e is string => !!e);
}

/** Conflitos entre os efeitos (as mesmas regras do servidor), na ordem do formulário. */
export function tabulationConflict(v: {
  effectiveContact: boolean;
  promise: boolean;
  callback: boolean;
  requiresNote: boolean;
  followUp: TabulationFollowUp;
}): string | null {
  if (v.promise && !v.effectiveContact) {
    return 'Promessa exige contato efetivo.';
  }
  if (v.promise && v.callback) {
    return 'Promessa e retorno são exclusivos.';
  }
  if (v.followUp !== 'NONE' && (v.promise || v.callback)) {
    return 'Tabulação com ação automática não registra promessa nem retorno.';
  }
  if (v.followUp !== 'NONE' && !v.requiresNote) {
    return 'Tabulação com ação automática exige observação (a justificativa fica com a interação).';
  }
  if (v.followUp === 'OPEN_DISPUTE' && !v.effectiveContact) {
    return 'Abrir contestação pressupõe contato efetivo com o devedor.';
  }
  return null;
}

/**
 * Cadastro de tabulação. Promessa e retorno são exclusivos; promessa pressupõe contato efetivo. A ação
 * automática executa o efeito do fato relatado no próprio registro (contestação, bloqueio por
 * falecimento, contato inválido): exige observação e não se combina com promessa ou retorno.
 */
@Component({
  selector: 'app-tabulation-form',
  imports: [ReactiveFormsModule, ...FORM_FIELD, ...DIALOG_DIRECTIVES, Icon],
  host: { class: 'd-flex flex-column overflow-hidden' },
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">{{ data ? 'Editar tabulação' : 'Nova tabulação' }}</h2>
      <button type="button" class="btn-close" aria-label="Fechar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="save()" novalidate>
      <div class="modal-body">
        @for (e of errors(); track e) {
          <div class="alert alert-danger d-flex gap-2 align-items-start" role="alert">{{ e }}</div>
        }
        <div class="app-form-grid gap-3">
          <app-field label="Nome">
            <input appInput formControlName="name" maxlength="120" required />
          </app-field>
          <app-field label="Código" [hint]="(data ? 'Imutável (usado nos relatórios).' : 'Vazio = gerado pelo nome.')">
            <input appInput formControlName="code" maxlength="40" [readonly]="!!data" />
          </app-field>
          <app-field label="Ordem de exibição">
            <input appInput type="number" formControlName="displayOrder" />
          </app-field>
        </div>
        <div class="tf-flags" role="group" aria-label="Efeitos da tabulação">
          <div class="form-check">
            <input class="form-check-input" type="checkbox" id="tabulation-list-effectiveContact" formControlName="effectiveContact" />
            <label class="form-check-label" for="tabulation-list-effectiveContact">Contato efetivo (CPC)</label>
          </div>
          <div class="form-check">
            <input class="form-check-input" type="checkbox" id="tabulation-list-promise" formControlName="promise" />
            <label class="form-check-label" for="tabulation-list-promise">Registra promessa de pagamento</label>
          </div>
          <div class="form-check">
            <input class="form-check-input" type="checkbox" id="tabulation-list-callback" formControlName="callback" />
            <label class="form-check-label" for="tabulation-list-callback">Agenda retorno</label>
          </div>
          <div class="form-check">
            <input class="form-check-input" type="checkbox" id="tabulation-list-requiresNote" formControlName="requiresNote" />
            <label class="form-check-label" for="tabulation-list-requiresNote">Observação obrigatória</label>
          </div>
        </div>
        <div class="app-form-grid gap-3 mt-3">
          <app-field label="Ação automática" hint="Executada no próprio registro da interação.">
            <select appInput formControlName="followUp">
              @for (f of followUps; track f.value) {
                <option [ngValue]="f.value">{{ f.label }}</option>
              }
            </select>
          </app-field>
          @if (followUp() === 'OPEN_DISPUTE') {
            <app-field label="Tipo de contestação pré-selecionado" hint="Vazio = o operador escolhe no registro.">
              <select appInput formControlName="disputeType">
                <option [ngValue]="null">O operador escolhe</option>
                @for (d of disputeTypes; track d.value) {
                  <option [ngValue]="d.value">{{ d.label }}</option>
                }
              </select>
            </app-field>
          }
        </div>
        @if (followUp() === 'REPORT_DECEASED') {
          <p class="app-alert app-alert--warning mt-3 mb-0" role="note">
            <app-icon name="exclamation-triangle" />
            <span>Bloqueia o contato com o devedor em todos os canais e credores até a supervisão rever. Use só para o falecimento informado.</span>
          </p>
        }
        @if (conflict()) {
          <p class="app-field-error" role="alert">{{ conflict() }}</p>
        }
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
        <button class="btn btn-primary" type="submit" [disabled]="saving()">Salvar</button>
      </div>
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
  protected readonly data = inject<TabulationView | null>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<TabulationView>>(DialogRef);
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
    followUp: this.fb.control<TabulationFollowUp>(this.data?.followUp ?? 'NONE'),
    disputeType: this.fb.control<DisputeType | null>(this.data?.disputeType ?? null),
  });
  protected readonly followUps = Object.entries(FOLLOW_UP_LABELS).map(([value, label]) => ({ value: value as TabulationFollowUp, label }));
  protected readonly disputeTypes = Object.entries(DISPUTE_TYPE_LABELS).map(([value, label]) => ({ value: value as DisputeType, label }));
  protected readonly followUp = signal<TabulationFollowUp>(this.data?.followUp ?? 'NONE');

  constructor() {
    // promessa pressupõe contato efetivo; promessa e retorno são exclusivos
    this.form.controls.promise.valueChanges.subscribe((on) => {
      if (on) {
        this.form.patchValue({ effectiveContact: true, callback: false }, { emitEvent: false });
      }
    });
    this.form.controls.callback.valueChanges.subscribe((on) => on && this.form.controls.promise.setValue(false, { emitEvent: false }));
    // ação automática: exige observação; contestação pressupõe contato efetivo; sem promessa e retorno
    this.form.controls.followUp.valueChanges.subscribe((f) => {
      this.followUp.set(f);
      if (f !== 'NONE') {
        this.form.patchValue({ requiresNote: true, promise: false, callback: false, ...(f === 'OPEN_DISPUTE' ? { effectiveContact: true } : {}) }, { emitEvent: false });
      }
      if (f !== 'OPEN_DISPUTE') {
        this.form.controls.disputeType.setValue(null, { emitEvent: false });
      }
    });
  }

  protected save(): void {
    const v = this.form.getRawValue();
    this.conflict.set(tabulationConflict(v));
    if (this.form.invalid || this.conflict() || this.saving()) {
      this.form.markAllAsTouched();
      return;
    }
    const request: TabulationRequest = {
      ...v,
      code: this.data ? null : v.code.trim() || null,
      name: v.name.trim(),
      disputeType: v.followUp === 'OPEN_DISPUTE' ? v.disputeType : null,
      displayOrder: Number(v.displayOrder) || 0,
    };
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
  imports: [CellDef, DataTable, PageHeader, StatusChip, Icon, NgbTooltip],
  template: `
    <app-page-header title="Tabulações" subtitle="Resultados de contato usados pelos operadores. Os efeitos definem o que o registro exige e como o caso avança.">
      <button class="btn btn-primary" type="button" (click)="edit(null)">
        <app-icon name="plus-lg" />
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
      emptyIcon="tag"
      emptyMessage="Nenhuma tabulação cadastrada."
    >
      <ng-template appCell="order" [appCellOf]="rows()" let-t><span class="app-numeric">{{ t.displayOrder }}</span></ng-template>
      <ng-template appCell="name" [appCellOf]="rows()" let-t>
        <strong>{{ t.name }}</strong>&ngsp;<span class="app-muted app-mono">{{ t.code }}</span>
      </ng-template>
      <ng-template appCell="effects" [appCellOf]="rows()" let-t>{{ effects(t).join(' · ') || 'Sem efeitos (registro simples)' }}</ng-template>
      <ng-template appCell="active" [appCellOf]="rows()" let-t><app-status-chip domain="active" [status]="t.active" /></ng-template>
      <ng-template appCell="actions" [appCellOf]="rows()" let-t>
        <button class="btn btn-sm btn-outline-secondary border-0" type="button" (click)="edit(t)" [attr.aria-label]="'Editar ' + t.name" ngbTooltip="Editar">
          <app-icon name="pencil" />
        </button>
        <button class="btn btn-sm btn-outline-secondary border-0" type="button" (click)="toggle(t)" [attr.aria-label]="(t.active ? 'Desativar ' : 'Ativar ') + t.name" [ngbTooltip]="t.active ? 'Desativar' : 'Ativar'">
          <app-icon [name]="t.active ? 'toggle-on' : 'toggle-off'" />
        </button>
      </ng-template>
    </app-data-table>
  `,
})
export class TabulationList {
  private readonly api = inject(TabulationsApi);
  private readonly dialog = inject(DialogService);
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
      .open<TabulationForm, TabulationView | null, TabulationView>(TabulationForm, { data: t })
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
