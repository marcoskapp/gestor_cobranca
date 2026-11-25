import { Component, computed, effect, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { finalize } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { toApiError, userMessage } from '../../../core/http/problem';
import { NotificationService } from '../../../core/notification/notification.service';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { Icon } from '../../../shared/components/icon/icon';
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

/** Âncora da seção em Configurações da assessoria (`/organizacao/configuracoes#contatos-recebidos`). */
export const CONTACT_NOVELTY_ANCHOR = 'contatos-recebidos';

/**
 * Seção "Contatos recebidos" de Configurações da assessoria: por quantos dias o contato que chega numa nova carga,
 * para um devedor que já existia, aparece como "Novo" (ficha, painel do caso, filtro da lista, critério de fila e de
 * segmento). Quem hospeda só a exibe com `DEBTOR_MANAGE` (a mesma do cadastro de contatos, exigida pelo servidor na
 * leitura e na gravação — não há modo só leitura).
 */
@Component({
  selector: 'app-contact-novelty-section',
  imports: [Icon, ...FORM_FIELD, ReactiveFormsModule, RouterLink, AppDatePipe, EmptyState, Skeleton],
  template: `
    <section class="app-card" [attr.aria-labelledby]="anchor" data-tour="contact-novelty-settings">
      <h2 class="app-card__title" [id]="anchor" tabindex="-1">Contatos recebidos</h2>
      <p class="app-muted cn-small">Destaque dos telefones, e-mails e endereços que chegam numa nova carga.</p>
      @if (resource.isLoading() && !settings()) {
        <app-skeleton variant="card" [rows]="2" />
      } @else if (resource.status() === 'error' && !settings()) {
        <app-empty-state icon="cloud-slash" tone="error" title="Não foi possível carregar a configuração." message="Verifique a conexão e tente novamente.">
          <button class="btn btn-outline-primary" type="button" (click)="resource.reload()">Tentar novamente</button>
        </app-empty-state>
      } @else if (settings(); as s) {
        <form [formGroup]="form" (ngSubmit)="save(s)" novalidate aria-label="Prazo do selo “Novo” dos contatos recebidos">
          <p class="app-muted cn-small">
            {{ s.customized ? 'Personalizado' + (s.updatedAt ? ' em ' + (s.updatedAt | appDate) : '') : 'Vale o padrão do sistema (' + s.defaultWindowDays + ' dias).' }}
          </p>
          @if (error()) {
            <div class="alert alert-danger d-flex gap-2 align-items-start" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></div>
          }
          <div class="app-form-grid">
            <app-field
              label="Prazo do selo “Novo” (dias)"
              [hint]="'O telefone, e-mail ou endereço recebido numa nova carga, para um devedor que já existia, fica como “Novo” até a primeira tentativa ou o fim deste prazo. Padrão: ' + s.defaultWindowDays + ' dias.'"
              [error]="'Entre ' + s.minWindowDays + ' e ' + s.maxWindowDays + ' dias.'"
            >
              <input appInput type="number" formControlName="windowDays" [min]="s.minWindowDays" [max]="s.maxWindowDays" step="1" required data-tour="contact-novelty-window" />
            </app-field>
          </div>
          <p class="app-muted cn-small">
            Vale para os contatos recebidos daqui em diante.&ngsp;@if (canListDebtors()) {
              Para ver quem tem contato novo, use o filtro
              <a routerLink="/devedores" [queryParams]="{ 'contato-novo': 1 }">Com contato novo</a> na lista de devedores.
            }
          </p>
          <div class="app-form-actions">
            <button class="btn btn-primary" type="submit" [disabled]="saving() || form.pristine">
              {{ saving() ? 'Salvando…' : 'Salvar prazo' }}
            </button>
          </div>
        </form>
      }
    </section>
  `,
  styles: `
    .cn-small {
      margin: 0 0 12px;
      font-size: var(--app-text-sm);
    }
  `,
})
export class ContactNoveltySection {
  private readonly api = inject(DebtorsApi);
  private readonly notifications = inject(NotificationService);
  private readonly auth = inject(AuthService);

  protected readonly anchor = CONTACT_NOVELTY_ANCHOR;
  /** O atalho para a lista só aparece para quem abre a lista (`/devedores` exige `DEBTOR_VIEW`). */
  protected readonly canListDebtors = computed(() => this.auth.hasPermission(Permission.DEBTOR_VIEW));
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
          this.notifications.success('Prazo do selo “Novo” salvo.');
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
