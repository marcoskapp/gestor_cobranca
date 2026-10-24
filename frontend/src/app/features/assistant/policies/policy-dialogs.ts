import { Component, computed, inject, signal } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { catchError, finalize, of, startWith, switchMap } from 'rxjs';
import { toApiError, userMessage } from '../../../core/http/problem';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { CreditorLookup, PortfolioLookup } from '../../organization/data/organization.lookups';
import { AssistantPoliciesApi } from '../data/assistant.api';
import { ASSISTANT_ERROR_LABELS, MODE_HINTS, MODE_LABELS } from '../data/assistant.labels';
import { AssistantMode, PolicyView } from '../data/assistant.models';

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
  imports: [ReactiveFormsModule, MatDialogModule, MatButtonModule, MatFormFieldModule, MatIconModule, MatInputModule, MatSelectModule, EntityPicker],
  template: `
    <h2 mat-dialog-title>Nova política do assistente</h2>
    <form [formGroup]="form" (ngSubmit)="create()" novalidate>
      <mat-dialog-content>
        <app-entity-picker [control]="form.controls.creditor" [source]="creditorSource" label="Credor (vazio = toda a assessoria)" [single]="true" />
        @if (form.controls.creditor.value.length) {
          <app-entity-picker [control]="form.controls.portfolio" [source]="portfolioSource" label="Carteira (opcional)" [single]="true" />
        }
        <div class="app-form-grid">
          <mat-form-field>
            <mat-label>Código</mat-label>
            <input matInput formControlName="code" maxlength="40" required />
            <mat-hint>minúsculas, dígitos e hífen</mat-hint>
            <mat-error>2 a 40: minúsculas, dígitos e hífen.</mat-error>
          </mat-form-field>
          <mat-form-field>
            <mat-label>Nome</mat-label>
            <input matInput formControlName="name" maxlength="120" required />
          </mat-form-field>
          <mat-form-field class="app-span-all">
            <mat-label>Modo inicial</mat-label>
            <mat-select formControlName="mode">
              @for (m of modes; track m[0]) {
                <mat-option [value]="m[0]">{{ m[1] }}</mat-option>
              }
            </mat-select>
            <mat-hint>{{ hints[value().mode] }}</mat-hint>
          </mat-form-field>
        </div>
        <p class="app-alert app-alert--info" role="note"><mat-icon aria-hidden="true">info</mat-icon><span>Implantação recomendada: comece em Sombra, depois Autônomo com aprovação e só então Autônomo, carteira a carteira.</span></p>
        @if (error()) {
          <p class="app-alert app-alert--error" role="alert"><mat-icon aria-hidden="true">error_outline</mat-icon><span>{{ error() }}</span></p>
        }
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button mat-button type="button" mat-dialog-close>Cancelar</button>
        <button mat-flat-button type="submit" [disabled]="sending()">Criar rascunho</button>
      </mat-dialog-actions>
    </form>
  `,
})
export class NewPolicyDialog {
  private readonly api = inject(AssistantPoliciesApi);
  private readonly ref = inject<MatDialogRef<NewPolicyDialog, PolicyView>>(MatDialogRef);
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
  imports: [ReactiveFormsModule, MatDialogModule, MatButtonModule, MatFormFieldModule, MatIconModule, MatInputModule, EntityPicker],
  template: `
    <h2 mat-dialog-title class="dp-title"><mat-icon aria-hidden="true">power_settings_new</mat-icon>Desligar o assistente numa carteira</h2>
    <form [formGroup]="form" (ngSubmit)="disable()" novalidate>
      <mat-dialog-content>
        <app-entity-picker [control]="form.controls.creditor" [source]="creditorSource" label="Credor" [single]="true" />
        <app-entity-picker [control]="form.controls.portfolio" [source]="portfolioSource" label="Carteira" [single]="true" />
        @if (effective.isLoading()) {
          <p class="app-muted" role="status">Consultando a política vigente…</p>
        } @else if (form.controls.portfolio.value.length) {
          <dl class="app-definition-list dp-impact" aria-label="Impacto do desligamento" data-tour="disable-impact">
            <dt>Política vigente hoje</dt>
            <dd>{{ current() ? current()!.name + ' · v' + current()!.policyVersion + ' · ' + modes[current()!.mode] : 'nenhuma (o assistente já não atua)' }}</dd>
            <dt>Efeito</dt>
            <dd>Versão "Desligado" da carteira publicada agora; vale a partir da próxima mensagem.</dd>
            <dt>Conversas em andamento</dt>
            <dd>As que o assistente conduz nesta carteira são transferidas para a fila humana (motivo "assistente desligado").</dd>
          </dl>
        }
        <mat-form-field class="app-full-width">
          <mat-label>Motivo</mat-label>
          <textarea matInput formControlName="reason" rows="2" maxlength="300" required></textarea>
          <mat-hint>Registrado na auditoria</mat-hint>
        </mat-form-field>
        <p class="app-alert app-alert--warning" role="note"><mat-icon aria-hidden="true">warning_amber</mat-icon><span>Para religar, publique uma nova versão da política da carteira.</span></p>
        <mat-form-field class="app-full-width">
          <mat-label>Digite {{ word }} para confirmar</mat-label>
          <input matInput formControlName="confirmation" autocomplete="off" />
        </mat-form-field>
        @if (error()) {
          <p class="app-alert app-alert--error" role="alert"><mat-icon aria-hidden="true">error_outline</mat-icon><span>{{ error() }}</span></p>
        }
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button mat-button type="button" mat-dialog-close cdkFocusInitial>Cancelar</button>
        <button mat-flat-button type="submit" class="app-button-danger" [disabled]="!confirmed() || sending()">Desligar agora</button>
      </mat-dialog-actions>
    </form>
  `,
  styles: `
    .dp-title {
      display: flex;
      align-items: center;
      gap: 8px;
    }
    .dp-impact {
      margin: 8px 0 16px;
      padding: 12px;
      border-radius: var(--app-radius-md);
      background: var(--app-surface-raised);
    }
  `,
})
export class DisablePortfolioDialog {
  private readonly data = inject<DisablePortfolioData>(MAT_DIALOG_DATA);
  private readonly api = inject(AssistantPoliciesApi);
  private readonly ref = inject<MatDialogRef<DisablePortfolioDialog, PolicyView>>(MatDialogRef);
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
