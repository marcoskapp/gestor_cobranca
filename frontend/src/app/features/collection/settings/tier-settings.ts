import { Component, effect, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { finalize } from 'rxjs';
import { Permission } from '../../../core/auth/permission';
import { NotificationService } from '../../../core/notification/notification.service';
import { ConfigLink } from '../../../shared/components/config-link/config-link';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { Icon } from '../../../shared/components/icon/icon';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { valueOr } from '../../../shared/data/resource-value';
import { FORM_FIELD } from '../../../shared/forms/field';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { CollectionSettingsApi } from '../data/collection.api';
import { PerformanceTier, TierSettingsRequest } from '../data/collection.models';

/** Limites de `CollectionSettingsController.TierSettingsRequest` (decisão do responsável de 12/11/2026). */
export const TIER_LIMITS = { capacity: { min: 50, max: 200 }, developmentShare: { min: 0, max: 50 } } as const;

export const TIERS: readonly PerformanceTier[] = ['A', 'B', 'C', 'D'];

/**
 * Há dois níveis padrão: o da classificação (quem fica sem amostra mínima na apuração) e este (quem ainda não tem
 * nível publicado na distribuição). Devem coincidir; a dica avisa e o atalho leva à classificação.
 */
export const DEFAULT_TIER_HINT =
  'Para quem ainda não tem nível publicado (ex.: recém-chegado). Deve ser igual ao nível padrão da Classificação de desempenho, usado para quem fica sem amostra mínima.';

const capacity = () => [Validators.required, Validators.min(TIER_LIMITS.capacity.min), Validators.max(TIER_LIMITS.capacity.max)];

/**
 * Parâmetros da distribuição por nível de desempenho (filas com a estratégia "Por nível de desempenho"): capacidade
 * por nível, fatia de desenvolvimento e nível de quem ainda não tem nível. Valem na próxima distribuição.
 */
@Component({
  selector: 'app-tier-settings',
  imports: [ReactiveFormsModule, EmptyState, Skeleton, AppDatePipe, Icon, ConfigLink, ...FORM_FIELD],
  template: `
    <h2 class="app-section-title mt-4" id="tier-settings-title">Distribuição por nível de desempenho</h2>
    @if (settings.isLoading() && !current()) {
      <app-skeleton variant="card" [rows]="3" />
    } @else if (settings.status() === 'error') {
      <app-empty-state icon="cloud-slash" tone="error" title="Não foi possível carregar a distribuição por nível.">
        <button class="btn btn-outline-primary" type="button" (click)="settings.reload()">Tentar novamente</button>
      </app-empty-state>
    } @else {
      <form class="app-card" [formGroup]="form" (ngSubmit)="save()" novalidate aria-labelledby="tier-settings-title" data-tour="tier-settings">
        <div class="alert alert-info d-flex gap-2 align-items-start" role="note">
          <app-icon name="info-circle" />
          <span>
            Usado nas filas com a estratégia "Por nível de desempenho". O nível de cada negociador vem da classificação de desempenho
            publicada após a revisão do supervisor; nível alto recebe primeiro os casos de maior valor esperado e mais casos.
            @if (current(); as s) {
              {{ s.customized ? 'Última alteração em ' + (s.updatedAt | appDate) + '.' : 'Valendo o padrão (A 120%, B 110%, C 100%, D 80%; 15%; nível B).' }}
            }
          </span>
        </div>
        <div class="app-form-grid gap-3">
          @for (tier of tiers; track tier) {
            <app-field [label]="'Capacidade do nível ' + tier + ' (%)'" hint="Percentual dos casos por operador da fila (50 a 200).">
              <input appInput type="number" [formControlName]="tier" min="50" max="200" required />
            </app-field>
          }
          <app-field label="Fatia de desenvolvimento (%)" hint="Parte dos casos de alto potencial que vai em rodízio a todos os níveis, para todos poderem melhorar (0 a 50).">
            <input appInput type="number" formControlName="developmentShare" min="0" max="50" required />
          </app-field>
          <app-field label="Nível de quem não tem nível" [hint]="defaultTierHint">
            <select appInput formControlName="defaultTier">
              @for (tier of tiers; track tier) {
                <option [value]="tier">Nível {{ tier }}</option>
              }
            </select>
            <app-config-link route="/remuneracao/classificacao" [queryParams]="classificationParams" label="Nível padrão da classificação" [permissions]="classificationPermissions" />
          </app-field>
        </div>
        <div class="app-form-actions">
          <button class="btn btn-primary" type="submit" [disabled]="saving() || form.pristine">Salvar distribuição por nível</button>
        </div>
      </form>
    }
  `,
})
export class TierSettings {
  private readonly api = inject(CollectionSettingsApi);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);

  protected readonly tiers = TIERS;
  protected readonly defaultTierHint = DEFAULT_TIER_HINT;
  protected readonly classificationParams = { aba: 'parametros' };
  protected readonly classificationPermissions = [Permission.TIER_MANAGE];
  protected readonly settings = rxResource({ stream: () => this.api.tierSettings() });
  protected readonly current = valueOr(this.settings, undefined);
  protected readonly saving = signal(false);

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    A: this.fb.control(120, capacity()),
    B: this.fb.control(110, capacity()),
    C: this.fb.control(100, capacity()),
    D: this.fb.control(80, capacity()),
    developmentShare: this.fb.control(15, [
      Validators.required,
      Validators.min(TIER_LIMITS.developmentShare.min),
      Validators.max(TIER_LIMITS.developmentShare.max),
    ]),
    defaultTier: this.fb.control<PerformanceTier>('B'),
  });

  constructor() {
    effect(() => {
      const s = this.current();
      if (s) {
        this.form.reset({ ...s.capacityPercent, developmentShare: s.developmentShare, defaultTier: s.defaultTier });
      }
    });
  }

  protected save(): void {
    if (this.form.invalid || this.saving()) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    const request: TierSettingsRequest = {
      capacityPercent: { A: Number(v.A), B: Number(v.B), C: Number(v.C), D: Number(v.D) },
      developmentShare: Number(v.developmentShare),
      defaultTier: v.defaultTier,
    };
    this.confirm
      .confirm({
        title: 'Alterar a distribuição por nível',
        message: 'Os novos valores valem a partir da próxima distribuição e do próximo "Próximo caso" nas filas por nível.',
        confirmLabel: 'Salvar',
        details: [
          { label: 'Capacidade', value: TIERS.map((t) => `${t} ${request.capacityPercent[t]}%`).join(' · ') },
          { label: 'Fatia de desenvolvimento', value: `${request.developmentShare}%` },
          { label: 'Nível de quem não tem nível', value: request.defaultTier },
        ],
      })
      .subscribe(() => {
        this.saving.set(true);
        this.api
          .updateTierSettings(request)
          .pipe(finalize(() => this.saving.set(false)))
          .subscribe((saved) => {
            this.settings.set(saved);
            this.notifications.success('Distribuição por nível salva.');
          });
      });
  }
}
