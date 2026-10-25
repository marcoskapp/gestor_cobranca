import { Component, computed, inject, signal } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { catchError, finalize, of, startWith, switchMap } from 'rxjs';
import { toApiError, userMessage } from '../../../core/http/problem';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { CreditorLookup, PortfolioLookup } from '../../organization/data/organization.lookups';
import { AssistantPoliciesApi } from '../data/assistant.api';
import { ASSISTANT_ERROR_LABELS, MODE_HINTS, MODE_LABELS } from '../data/assistant.labels';
import { AssistantMode, PolicyView } from '../data/assistant.models';
import { Icon } from '../../../shared/components/icon/icon';
import { FORM_FIELD } from '../../../shared/forms/field';
import { DIALOG_DATA, DialogRef } from '../../../shared/dialog/dialog-ref';
import { DIALOG_DIRECTIVES } from '../../../shared/dialog/dialog.directives';

const errorText = (err: unknown) => {
  const e = toApiError(err);
  return (e.code && ASSISTANT_ERROR_LABELS[e.code]) || userMessage(e);
};

/**
 * Nova política: escopo (assessoria, credor ou carteira — a mais específica vence), código, nome e
 * modo; os parâmetros nascem do modelo do servidor para o modo e são ajustados no rascunho.
 */
@Component({
  selector: 'app-new-policy-dialog',
  imports: [ReactiveFormsModule, ...DIALOG_DIRECTIVES, ...FORM_FIELD, EntityPicker, Icon],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">Nova política do assistente</h2>
      <button type="button" class="btn-close" aria-label="Cancelar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="create()" novalidate>
      <div class="modal-body">
        <app-entity-picker [control]="form.controls.creditor" [source]="creditorSource" label="Credor (vazio = toda a assessoria)" [single]="true" class="mb-3" />
        @if (form.controls.creditor.value.length) {
          <app-entity-picker [control]="form.controls.portfolio" [source]="portfolioSource" label="Carteira (opcional)" [single]="true" class="mb-3" />
        }
        <div class="app-form-grid row-gap-3">
          <app-field label="Código" hint="minúsculas, dígitos e hífen" error="2 a 40: minúsculas, dígitos e hífen.">
            <input appInput formControlName="code" maxlength="40" required ngbAutofocus />
          </app-field>
          <app-field label="Nome">
            <input appInput formControlName="name" maxlength="120" required />
          </app-field>
          <app-field class="app-span-all" label="Modo inicial" [hint]="(hints[value().mode])">
            <select appInput formControlName="mode">
              @for (m of modes; track m[0]) {
                <option [ngValue]="m[0]">{{ m[1] }}</option>
              }
            </select>
          </app-field>
        </div>
        <div class="alert alert-info d-flex align-items-start gap-2 mt-3" role="note"><app-icon name="info-circle" /><span>Implantação recomendada: comece em Sombra, depois Autônomo com aprovação e só então Autônomo, carteira a carteira.</span></div>
        @if (error()) {
          <div class="alert alert-danger d-flex align-items-start gap-2" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></div>
        }
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
        <button class="btn btn-primary" type="submit" [disabled]="sending()">Criar rascunho</button>
      </div>
    </form>
  `,
})
export class NewPolicyDialog {
  private readonly api = inject(AssistantPoliciesApi);
  private readonly ref = inject<DialogRef<PolicyView>>(DialogRef);
  protected readonly creditorSource = inject(CreditorLookup);
  protected readonly modes = Object.entries(MODE_LABELS) as [AssistantMode, string][];
  protected readonly hints = MODE_HINTS;
  protected readonly sending = signal(false);
  protected readonly error = signal<string | null>(null);
  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    creditor: this.fb.control<string[]>([]),
    portfolio: this.fb.control<string[]>([]),
    code: ['', [Validators.required, Validators.pattern(/^[a-z0-9-]{2,40}$/)]],
    name: ['', [Validators.required, Validators.maxLength(120)]],
    mode: this.fb.control<AssistantMode>('SHADOW'),
  });
  protected readonly portfolioSource = inject(PortfolioLookup).source(() => this.form.controls.creditor.value[0] ?? null);
  private readonly values = toSignal(this.form.valueChanges.pipe(startWith(null)), { initialValue: null });
  protected readonly value = computed(() => {
    this.values();
    return this.form.getRawValue();
  });

  protected create(): void {
    const v = this.form.getRawValue();
    if (this.form.invalid || this.sending()) {
      this.form.markAllAsTouched();
      return;
    }
    this.sending.set(true);
    this.error.set(null);
    this.api
      .template(v.mode)
      .pipe(
        switchMap((settings) =>
          this.api.create({ creditorId: v.creditor[0] ?? null, portfolioId: v.creditor[0] ? (v.portfolio[0] ?? null) : null, code: v.code.trim(), name: v.name.trim(), settings }),
        ),
        finalize(() => this.sending.set(false)),
      )
      .subscribe({ next: (p) => this.ref.close(p), error: (err: unknown) => this.error.set(errorText(err)) });
  }
}

/** Palavra que confirma o desligamento da carteira (efeito imediato nas conversas em andamento). */
export const DISABLE_WORD = 'DESLIGAR';

export interface DisablePortfolioData {
  creditorId: string | null;
  portfolioId: string | null;
}

/**
 * Desligamento imediato do assistente numa carteira: publica uma versão `OFF` da carteira na hora e
 * transfere para humanos as conversas que o assistente conduz nela. Mostra a política vigente
 * (o que deixa de valer) antes de confirmar.
 */
@Component({
  selector: 'app-disable-portfolio-dialog',
  imports: [ReactiveFormsModule, ...DIALOG_DIRECTIVES, ...FORM_FIELD, EntityPicker, Icon],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0 d-flex align-items-center gap-2"><app-icon name="power" />Desligar o assistente numa carteira</h2>
      <button type="button" class="btn-close" aria-label="Cancelar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="disable()" novalidate>
      <div class="modal-body vstack gap-3">
        <app-entity-picker [control]="form.controls.creditor" [source]="creditorSource" label="Credor" [single]="true" />
        <app-entity-picker [control]="form.controls.portfolio" [source]="portfolioSource" label="Carteira" [single]="true" />
        @if (effective.isLoading()) {
          <p class="app-muted mb-0" role="status">Consultando a política vigente…</p>
        } @else if (form.controls.portfolio.value.length) {
          <dl class="app-definition-list dp-impact mb-0" aria-label="Impacto do desligamento" data-tour="disable-impact">
            <dt>Política vigente hoje</dt>
            <dd>{{ current() ? current()!.name + ' · v' + current()!.policyVersion + ' · ' + modes[current()!.mode] : 'nenhuma (o assistente já não atua)' }}</dd>
            <dt>Efeito</dt>
            <dd>Versão "Desligado" da carteira publicada agora; vale a partir da próxima mensagem.</dd>
            <dt>Conversas em andamento</dt>
            <dd>As que o assistente conduz nesta carteira são transferidas para a fila humana (motivo "assistente desligado").</dd>
          </dl>
        }
        <app-field label="Motivo" hint="Registrado na auditoria">
          <textarea appInput formControlName="reason" rows="2" maxlength="300" required></textarea>
        </app-field>
        <div class="alert alert-warning d-flex align-items-start gap-2 mb-0" role="note"><app-icon name="exclamation-triangle" /><span>Para religar, publique uma nova versão da política da carteira.</span></div>
        <app-field [label]="'Digite ' + (word) + ' para confirmar'">
          <input appInput formControlName="confirmation" autocomplete="off" />
        </app-field>
        @if (error()) {
          <div class="alert alert-danger d-flex align-items-start gap-2 mb-0" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></div>
        }
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose ngbAutofocus>Cancelar</button>
        <button type="submit" class="btn btn-danger" [disabled]="!confirmed() || sending()">Desligar agora</button>
      </div>
    </form>
  `,
  styles: `
    .dp-impact {
      padding: 12px;
      border-radius: var(--bs-border-radius);
      background: var(--bs-tertiary-bg);
    }
  `,
})
export class DisablePortfolioDialog {
  private readonly data = inject<DisablePortfolioData>(DIALOG_DATA);
  private readonly api = inject(AssistantPoliciesApi);
  private readonly ref = inject<DialogRef<PolicyView>>(DialogRef);
  protected readonly creditorSource = inject(CreditorLookup);
  protected readonly modes = MODE_LABELS;
  protected readonly word = DISABLE_WORD;
  protected readonly sending = signal(false);
  protected readonly error = signal<string | null>(null);
  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    creditor: this.fb.control<string[]>(this.data.creditorId ? [this.data.creditorId] : []),
    portfolio: this.fb.control<string[]>(this.data.portfolioId ? [this.data.portfolioId] : []),
    reason: ['', [Validators.required, Validators.maxLength(300)]],
    confirmation: [''],
  });
  protected readonly portfolioSource = inject(PortfolioLookup).source(() => this.form.controls.creditor.value[0] ?? null);
  private readonly values = toSignal(this.form.valueChanges.pipe(startWith(null)), { initialValue: null });
  private readonly target = computed(() => {
    this.values();
    const v = this.form.getRawValue();
    return v.creditor[0] && v.portfolio[0] ? { creditorId: v.creditor[0], portfolioId: v.portfolio[0] } : undefined;
  });
  protected readonly effective = rxResource({
    params: () => this.target(),
    stream: ({ params }) => this.api.effective(params.creditorId, params.portfolioId).pipe(catchError(() => of(null))),
  });
  protected readonly current = computed(() => (this.effective.hasValue() ? this.effective.value() : null));
  protected readonly confirmed = computed(() => {
    this.values();
    const v = this.form.getRawValue();
    return !!this.target() && v.reason.trim().length > 0 && v.confirmation.trim().toUpperCase() === DISABLE_WORD;
  });

  protected disable(): void {
    const t = this.target();
    if (!t || !this.confirmed() || this.sending()) {
      return;
    }
    this.sending.set(true);
    this.error.set(null);
    this.api
      .disablePortfolio(t.portfolioId, this.form.getRawValue().reason.trim())
      .pipe(finalize(() => this.sending.set(false)))
      .subscribe({ next: (p) => this.ref.close(p), error: (err: unknown) => this.error.set(errorText(err)) });
  }
}
