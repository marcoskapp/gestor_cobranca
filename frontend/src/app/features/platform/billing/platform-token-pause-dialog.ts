import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Observable, finalize } from 'rxjs';
import { toApiError, userMessage } from '../../../core/http/problem';
import { Icon } from '../../../shared/components/icon/icon';
import { DIALOG_DATA, DialogRef } from '../../../shared/dialog/dialog-ref';
import { DIALOG_DIRECTIVES } from '../../../shared/dialog/dialog.directives';
import { FORM_FIELD } from '../../../shared/forms/field';
import { PlatformBillingApi } from '../data/platform-billing.api';
import { BulkTokenResult, PlatformTokenView } from '../data/platform-billing.models';
import { PLATFORM_BILLING_ERRORS } from './platform-billing.labels';

export interface PlatformTokenPauseDialogData {
  tenantId: string;
  tenantName: string;
  /** Token a pausar; ausente = todos os tokens ativos da assessoria. */
  token?: PlatformTokenView;
}

/** Prazo máximo da retomada automática (o servidor confere de novo). */
export const MAX_RESUME_DAYS = 90;

/**
 * Valida a retomada automática digitada (`datetime-local`, fuso do navegador): vazia = sem retomada; senão no futuro e
 * em até 90 dias. Devolve a mensagem do problema, ou `null`.
 */
export function resumeProblem(value: string, now: Date = new Date()): string | null {
  if (!value) {
    return null;
  }
  const at = new Date(value);
  if (Number.isNaN(at.getTime())) {
    return 'Data e hora inválidas.';
  }
  if (at.getTime() <= now.getTime()) {
    return 'A retomada automática precisa ser no futuro.';
  }
  if (at.getTime() > now.getTime() + MAX_RESUME_DAYS * 86_400_000) {
    return `A retomada automática pode ser agendada para até ${MAX_RESUME_DAYS} dias.`;
  }
  return null;
}

/**
 * Pausa pela plataforma de um token (ou de todos os tokens ativos da assessoria): motivo obrigatório e retomada
 * automática opcional. A pausa vale na próxima chamada; a assessoria vê o motivo e não pode retomar.
 */
@Component({
  selector: 'app-platform-token-pause-dialog',
  imports: [ReactiveFormsModule, ...DIALOG_DIRECTIVES, ...FORM_FIELD, Icon],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0" tabindex="-1" ngbAutofocus>
        {{ data.token ? 'Pausar token' : 'Pausar todos os tokens' }} — {{ data.tenantName }}
      </h2>
      <button type="button" class="btn-close" aria-label="Fechar" appDialogClose></button>
    </div>
    <form [formGroup]="form" (ngSubmit)="save()" novalidate>
      <div class="modal-body">
        @if (error()) {
          <div class="alert alert-danger d-flex gap-2 align-items-start" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></div>
        }
        <div class="alert alert-warning d-flex gap-2 align-items-start" role="note">
          <app-icon name="pause-circle" />
          <span>
            @if (data.token) {
              As chamadas com <strong class="app-mono">{{ data.token.prefix }}</strong> ({{ data.token.name }}) passam a ser recusadas
              já na próxima requisição, sem cobrança.
            } @else {
              Todos os tokens ativos da assessoria passam a ser recusados já na próxima requisição, sem cobrança.
            }
            A assessoria é avisada por e-mail, vê o motivo e não pode retomar; continua podendo revogar.
          </span>
        </div>
        <app-field label="Motivo" hint="Obrigatório. A assessoria vê este texto na tela do token; fica nas trilhas de auditoria.">
          <textarea appInput formControlName="reason" rows="3" maxlength="500" required></textarea>
        </app-field>
        <app-field label="Retomar automaticamente em" hint="Opcional, até 90 dias. Sem data, o token fica pausado até a plataforma retomar." [error]="resumeError() ?? undefined">
          <input appInput type="datetime-local" formControlName="resumeAt" />
        </app-field>
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
        <button class="btn btn-warning" type="submit" [disabled]="saving()">{{ data.token ? 'Pausar token' : 'Pausar todos' }}</button>
      </div>
    </form>
  `,
})
export class PlatformTokenPauseDialog {
  protected readonly data = inject<PlatformTokenPauseDialogData>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<PlatformTokenView | BulkTokenResult>>(DialogRef);
  private readonly api = inject(PlatformBillingApi);
  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly resumeError = signal<string | null>(null);

  protected readonly form = this.fb.group({
    reason: ['', [Validators.required, Validators.maxLength(500)]],
    resumeAt: [''],
  });

  protected save(): void {
    this.form.markAllAsTouched();
    const v = this.form.getRawValue();
    this.resumeError.set(resumeProblem(v.resumeAt));
    if (this.form.invalid || !v.reason.trim() || this.resumeError() || this.saving()) {
      return;
    }
    const resumeAt = v.resumeAt ? new Date(v.resumeAt).toISOString() : null;
    const reason = v.reason.trim();
    const call: Observable<PlatformTokenView | BulkTokenResult> = this.data.token
      ? this.api.pauseToken(this.data.tenantId, this.data.token.id, reason, resumeAt)
      : this.api.pauseAllTokens(this.data.tenantId, reason, resumeAt);
    this.saving.set(true);
    this.error.set(null);
    call.pipe(finalize(() => this.saving.set(false))).subscribe({
      next: (result) => this.ref.close(result),
      error: (err: unknown) => {
        const e = toApiError(err);
        this.error.set((e.code && PLATFORM_BILLING_ERRORS[e.code]) || userMessage(e));
      },
    });
  }
}
