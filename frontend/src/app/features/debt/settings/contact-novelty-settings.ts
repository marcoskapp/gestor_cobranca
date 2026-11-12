import { Component, effect, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { finalize } from 'rxjs';
import { toApiError, userMessage } from '../../../core/http/problem';
import { NotificationService } from '../../../core/notification/notification.service';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { Icon } from '../../../shared/components/icon/icon';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { valueOr } from '../../../shared/data/resource-value';
import { FORM_FIELD } from '../../../shared/forms/field';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { applyServerErrors } from '../../../shared/validation/server-errors';
import { DebtorsApi } from '../data/debt.api';
import { ContactNoveltySettings as ContactNoveltySettingsView } from '../data/debt.models';

/** Limites do prazo quando o servidor ainda não respondeu (o servidor sempre valida). */
const MIN_DAYS = 1;
const MAX_DAYS = 180;

/**
 * Configurações › Contatos recebidos: por quantos dias o contato que chega numa nova carga, para um devedor que já
 * existia, aparece como "Novo" (ficha, painel do caso, filtro da lista, critério de fila e de segmento). A rota exige
 * `DEBTOR_MANAGE` (a mesma do cadastro de contatos); o servidor confere a permissão.
 */
@Component({
  selector: 'app-contact-novelty-settings',
  imports: [Icon, ...FORM_FIELD, ReactiveFormsModule, RouterLink, AppDatePipe, EmptyState, PageHeader, Skeleton],
  template: `
    <app-page-header title="Contatos recebidos" subtitle="Destaque dos telefones, e-mails e endereços que chegam numa nova carga." />
    @if (resource.isLoading() && !settings()) {
      <app-skeleton variant="card" [rows]="3" />
    } @else if (resource.status() === 'error' && !settings()) {
      <app-empty-state icon="cloud-slash" tone="error" title="Não foi possível carregar a configuração." message="Verifique a conexão e tente novamente.">
        <button class="btn btn-outline-primary" type="button" (click)="resource.reload()">Tentar novamente</button>
      </app-empty-state>
    } @else if (settings(); as s) {
      <form class="app-card cn-card" [formGroup]="form" (ngSubmit)="save(s)" novalidate data-tour="contact-novelty-settings">
        <p class="app-muted cn-small">
          {{ s.customized ? 'Personalizado' + (s.updatedAt ? ' em ' + (s.updatedAt | appDate) : '') : 'Vale o padrão do sistema (' + s.defaultWindowDays + ' dias).' }}
        </p>
        @if (error()) {
          <div class="alert alert-danger d-flex gap-2 align-items-start" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></div>
        }
        <div class="app-form-grid">
          <app-field
            label="Janela de novidade (dias)"
            [hint]="'Contato recebido numa nova carga para devedor que já existia fica como “Novo” até a primeira tentativa ou o fim deste prazo. Padrão: ' + s.defaultWindowDays + ' dias.'"
            [error]="'Entre ' + s.minWindowDays + ' e ' + s.maxWindowDays + ' dias.'"
          >
            <input appInput type="number" formControlName="windowDays" [min]="s.minWindowDays" [max]="s.maxWindowDays" step="1" required data-tour="contact-novelty-window" />
          </app-field>
        </div>
        <p class="app-muted cn-small">
          Vale para os contatos recebidos daqui em diante. Para ver quem tem contato novo, use o filtro
          <a routerLink="/devedores" [queryParams]="{ 'contato-novo': 1 }">Com contato novo</a> na lista de devedores.
        </p>
        <div class="app-form-actions">
          <button class="btn btn-primary" type="submit" [disabled]="saving() || form.pristine">
            {{ saving() ? 'Salvando…' : 'Salvar' }}
          </button>
        </div>
      </form>
    }
  `,
  styles: `
    .cn-card {
      max-width: 880px;
    }
    .cn-small {
      margin: 0 0 12px;
      font-size: var(--app-text-sm);
    }
  `,
})
export class ContactNoveltySettings {
  private readonly api = inject(DebtorsApi);
  private readonly notifications = inject(NotificationService);

  protected readonly resource = rxResource({ stream: () => this.api.contactNoveltySettings() });
  protected readonly settings = valueOr<ContactNoveltySettingsView | undefined>(this.resource, undefined);
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly form = inject(FormBuilder).nonNullable.group({
    windowDays: [30, [Validators.required, Validators.min(MIN_DAYS), Validators.max(MAX_DAYS)]],
  });

  constructor() {
    effect(() => {
      const s = this.settings();
      if (s) {
        const control = this.form.controls.windowDays;
        control.setValidators([Validators.required, Validators.min(s.minWindowDays), Validators.max(s.maxWindowDays)]);
        this.form.reset({ windowDays: s.windowDays });
      }
    });
  }

  protected save(current: ContactNoveltySettingsView): void {
    if (this.form.invalid || this.saving()) {
      this.form.markAllAsTouched();
      return;
    }
    const days = Math.trunc(Number(this.form.getRawValue().windowDays));
    if (days === current.windowDays && current.customized) {
      return;
    }
    this.saving.set(true);
    this.error.set(null);
    this.api
      .updateContactNoveltySettings(days)
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe({
        next: (saved) => {
          this.notifications.success('Janela de novidade salva.');
          this.resource.set(saved);
        },
        error: (err: unknown) => {
          const apiError = toApiError(err);
          const unmatched = applyServerErrors(this.form, apiError);
          if (!apiError.fieldErrors.length || unmatched.length) {
            this.error.set(userMessage(apiError));
          }
        },
      });
  }
}
