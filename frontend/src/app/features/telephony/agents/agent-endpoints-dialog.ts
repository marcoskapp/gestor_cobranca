import { Component, WritableSignal, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { Observable, finalize, forkJoin, map, of } from 'rxjs';
import { toApiError, userMessage } from '../../../core/http/problem';
import { Icon } from '../../../shared/components/icon/icon';
import { DIALOG_DATA, DialogRef } from '../../../shared/dialog/dialog-ref';
import { DIALOG_DIRECTIVES } from '../../../shared/dialog/dialog.directives';
import { FORM_FIELD } from '../../../shared/forms/field';
import { AgentsApi } from '../data/telephony.api';
import { PROVIDER_LABELS, TELEPHONY_ERROR_LABELS } from '../data/telephony.labels';
import { AgentView, EndpointView, TelephonyAccountView } from '../data/telephony.models';

export type EndpointMode = 'DEFAULT' | 'OWN' | 'OFF';

export interface AgentEndpointsDialogData {
  agent: AgentView;
  userName: string;
  accounts: TelephonyAccountView[];
  endpoints: EndpointView[];
}

interface Row {
  account: TelephonyAccountView;
  current: EndpointView | null;
  mode: FormControl<EndpointMode>;
  /** Espelho do modo para o template (mostra o campo do ramal próprio). */
  selected: WritableSignal<EndpointMode>;
  extension: FormControl<string>;
}

/** Modo atual de uma conta para o operador: sem registro = ramal padrão. */
export function endpointMode(endpoint: EndpointView | null): EndpointMode {
  return !endpoint ? 'DEFAULT' : endpoint.active ? 'OWN' : 'OFF';
}

/**
 * Ramal do operador em cada plataforma. "Ramal padrão" usa o ramal cadastrado no operador (útil
 * quando todas as plataformas alcançam o mesmo endereço SIP); "ramal próprio" vale só nesta conta;
 * "fora desta plataforma" impede que a conta lhe entregue chamadas ou disque por ele.
 */
@Component({
  selector: 'app-agent-endpoints-dialog',
  imports: [ReactiveFormsModule, ...DIALOG_DIRECTIVES, ...FORM_FIELD, Icon],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">Ramais de {{ data.userName }} por plataforma</h2>
      <button type="button" class="btn-close" aria-label="Cancelar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="save()" novalidate>
      <div class="modal-body d-grid gap-3">
        <p class="app-muted mb-0">Ramal padrão: {{ data.agent.extension }}.</p>
        @for (r of rows; track r.account.id) {
          <fieldset class="border rounded p-3">
            <legend class="fs-6 fw-semibold float-none w-auto px-1 mb-0">{{ r.account.name }} <span class="app-muted fw-normal">— {{ labels[r.account.provider] }}</span></legend>
            <div class="row g-2 align-items-end">
              <app-field class="col-sm-6" label="Nesta conta">
                <select appInput [formControl]="r.mode">
                  <option [ngValue]="'DEFAULT'">Usa o ramal padrão</option>
                  <option [ngValue]="'OWN'">Ramal próprio</option>
                  <option [ngValue]="'OFF'">Fora desta plataforma</option>
                </select>
              </app-field>
              @if (r.selected() === 'OWN') {
                <app-field class="col-sm-6" label="Ramal ou usuário SIP" error="Informe o ramal.">
                  <input appInput [formControl]="r.extension" maxlength="64" autocomplete="off" required />
                </app-field>
              }
            </div>
          </fieldset>
        } @empty {
          <p class="mb-0">Nenhuma conta de telefonia cadastrada.</p>
        }
        @if (error()) {
          <p class="alert alert-danger d-flex gap-2 mb-0" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></p>
        }
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
        <button class="btn btn-primary" type="submit" [disabled]="saving()">Salvar ramais</button>
      </div>
    </form>
  `,
})
export class AgentEndpointsDialog {
  /** Agrupa o formulário (envio por ngSubmit). */
  protected readonly form = new FormGroup({});
  protected readonly data = inject<AgentEndpointsDialogData>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<boolean>>(DialogRef);
  private readonly api = inject(AgentsApi);
  protected readonly labels = PROVIDER_LABELS;
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly rows: Row[] = this.data.accounts.map((account) => {
    const current = this.data.endpoints.find((e) => e.accountId === account.id && e.userId === this.data.agent.userId) ?? null;
    const mode = new FormControl<EndpointMode>(endpointMode(current), { nonNullable: true });
    const selected = signal(mode.value);
    mode.valueChanges.subscribe((m) => selected.set(m));
    return {
      account,
      current,
      mode,
      selected,
      extension: new FormControl(current?.extension ?? '', { nonNullable: true, validators: [Validators.required, Validators.maxLength(64)] }),
    };
  });

  protected save(): void {
    const invalid = this.rows.filter((r) => r.mode.value === 'OWN' && r.extension.invalid);
    if (invalid.length || this.saving()) {
      invalid.forEach((r) => r.extension.markAsTouched());
      return;
    }
    const userId = this.data.agent.userId;
    const changes: Observable<unknown>[] = this.rows
      .filter((r) => r.mode.value !== endpointMode(r.current) || (r.mode.value === 'OWN' && r.extension.value.trim() !== (r.current?.extension ?? '')))
      .map((r) =>
        r.mode.value === 'DEFAULT'
          ? this.api.removeEndpoint(userId, r.account.id)
          : this.api.configureEndpoint(userId, r.account.id, r.mode.value === 'OWN' ? r.extension.value.trim() : null, r.mode.value === 'OWN'),
      );
    this.saving.set(true);
    this.error.set(null);
    (changes.length ? forkJoin(changes).pipe(map(() => true)) : of(false))
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe({
        next: (changed) => this.ref.close(changed),
        error: (err: unknown) => {
          const e = toApiError(err);
          this.error.set((e.code && TELEPHONY_ERROR_LABELS[e.code]) || userMessage(e));
        },
      });
  }
}
