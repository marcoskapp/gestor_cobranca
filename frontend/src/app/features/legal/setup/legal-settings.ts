import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { catchError, finalize, of } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { NotificationService } from '../../../core/notification/notification.service';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { valueOr } from '../../../shared/data/resource-value';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { FormDialogService } from '../components/form-dialog';
import { LegalOperationsApi, LegalSetupApi } from '../data/legal.api';
import { ADVANCER_LABELS, BUDGET_LABELS, CONDUCT_LABELS, HOLD_LABELS, PRESCRIBED_LABELS } from '../data/legal.labels';
import { LegalSettings, SuspensionView } from '../data/legal.models';

/** "5, 2, 0" → [5, 2, 0] (0 a 30, sem repetição, do maior para o menor). */
export function parseAlertLevels(text: string): number[] | null {
  const parts = text.split(/[,;\s]+/).filter(Boolean).map(Number);
  if (!parts.length || parts.length > 5 || parts.some((n) => !Number.isInteger(n) || n < 0 || n > 30)) {
    return null;
  }
  return [...new Set(parts)].sort((a, b) => b - a);
}

/**
 * Parâmetros do jurídico da assessoria. Os três primeiros são decisões pendentes do responsável
 * (issue #14) com padrão conservador. Inclui as suspensões forenses que recalculam os prazos.
 */
@Component({
  selector: 'app-legal-settings',
  imports: [ReactiveFormsModule, MatButtonModule, MatFormFieldModule, MatIconModule, MatInputModule, MatSelectModule, MatSlideToggleModule, EmptyState, PageHeader, Skeleton, AppDatePipe],
  template: `
    <app-page-header title="Parâmetros do jurídico" subtitle="Quem conduz, quem adianta custas, orçamento, prescrição, retenção da cobrança e prazos." />

    @if (settings.isLoading() && !current()) {
      <app-skeleton variant="card" [rows]="6" />
    } @else if (settings.status() === 'error') {
      <app-empty-state icon="cloud_off" tone="error" title="Não foi possível carregar os parâmetros.">
        <button mat-stroked-button type="button" (click)="settings.reload()">Tentar novamente</button>
      </app-empty-state>
    } @else {
      <form class="app-card ls-form" [formGroup]="form" (ngSubmit)="save()" novalidate aria-labelledby="ls-title" data-tour="legal-settings">
        <h2 class="app-card__title" id="ls-title">Operação</h2>
        <p class="app-alert app-alert--warning" role="note">
          <mat-icon aria-hidden="true">pending_actions</mat-icon>
          <span>Condução, adiantamento de custas e títulos prescritos aguardam a decisão do responsável (issue #14); os padrões são conservadores.</span>
        </p>
        <div class="ls-grid">
          <mat-form-field>
            <mat-label>Quem conduz os casos</mat-label>
            <mat-select formControlName="conductModel">
              @for (o of conduct; track o.value) {
                <mat-option [value]="o.value">{{ o.label }}</mat-option>
              }
            </mat-select>
          </mat-form-field>
          <mat-form-field>
            <mat-label>Quem pode adiantar custas</mat-label>
            <mat-select formControlName="allowedExpenseAdvancers" multiple>
              @for (o of advancers; track o.value) {
                <mat-option [value]="o.value">{{ o.label }}</mat-option>
              }
            </mat-select>
          </mat-form-field>
          <mat-form-field>
            <mat-label>Orçamento de custas</mat-label>
            <mat-select formControlName="budgetEnforcement">
              @for (o of budget; track o.value) {
                <mat-option [value]="o.value">{{ o.label }}</mat-option>
              }
            </mat-select>
          </mat-form-field>
          <mat-form-field>
            <mat-label>Títulos com cobrança prescrita</mat-label>
            <mat-select formControlName="prescribedTitlePolicy">
              @for (o of prescribed; track o.value) {
                <mat-option [value]="o.value">{{ o.label }}</mat-option>
              }
            </mat-select>
          </mat-form-field>
          <mat-form-field>
            <mat-label>Reter a cobrança amigável</mat-label>
            <mat-select formControlName="collectionHoldStage">
              @for (o of hold; track o.value) {
                <mat-option [value]="o.value">{{ o.label }}</mat-option>
              }
            </mat-select>
          </mat-form-field>
        </div>

        <h2 class="app-card__title">Prazos</h2>
        <div class="ls-grid">
          <mat-form-field>
            <mat-label>Alertas (dias úteis restantes)</mat-label>
            <input matInput formControlName="alertLevels" />
            <mat-hint>Separados por vírgula, de 0 a 30. Padrão: 5, 2, 0.</mat-hint>
            @if (form.controls.alertLevels.invalid) {
              <mat-error>Informe até 5 números entre 0 e 30.</mat-error>
            }
          </mat-form-field>
          <mat-form-field>
            <mat-label>Baixa de restrição após pagamento (dias úteis)</mat-label>
            <input matInput type="number" min="1" max="30" formControlName="removalDeadlineBusinessDays" />
            <mat-hint>Protesto e negativação; Súmula 548/STJ: 5 dias úteis.</mat-hint>
          </mat-form-field>
          <mat-form-field>
            <mat-label>Homologação do acordo (dias úteis)</mat-label>
            <input matInput type="number" min="1" max="60" formControlName="homologationDeadlineBusinessDays" />
            <mat-hint>Prazo para protocolar o acordo judicial.</mat-hint>
          </mat-form-field>
          <mat-form-field>
            <mat-label>Comunicação prévia à negativação (dias)</mat-label>
            <input matInput type="number" min="1" max="60" formControlName="creditBureauNoticeDays" />
            <mat-hint>CDC art. 43 §2º; padrão 10.</mat-hint>
          </mat-form-field>
          <mat-form-field>
            <mat-label>Aviso de prescrição (dias antes)</mat-label>
            <input matInput type="number" min="1" max="1825" formControlName="prescriptionWarningDays" />
          </mat-form-field>
          <mat-form-field>
            <mat-label>Carência para nova indicação (dias)</mat-label>
            <input matInput type="number" min="0" max="1825" formControlName="referralCooldownDays" />
            <mat-hint>Após o encerramento, o caso não é indicado de novo automaticamente.</mat-hint>
          </mat-form-field>
        </div>
        <mat-slide-toggle formControlName="yearEndRecess">Recesso forense de 20/12 a 20/01 nos prazos em dias úteis (CPC art. 220)</mat-slide-toggle>
        @if (canManage()) {
          <div class="app-form-actions">
            <button mat-flat-button type="submit" [disabled]="saving() || form.pristine">Salvar parâmetros</button>
          </div>
        }
      </form>

      <section class="app-card" aria-labelledby="ls-suspensions">
        <div class="ls-head">
          <h2 class="app-card__title" id="ls-suspensions">Suspensões forenses</h2>
          @if (canManage()) {
            <button mat-stroked-button type="button" (click)="addSuspension()"><mat-icon aria-hidden="true">add</mat-icon>Nova suspensão</button>
          }
        </div>
        <p class="app-muted">Períodos sem expediente além dos feriados (por tribunal ou para todos). Incluir ou remover recalcula os prazos pendentes.</p>
        <ul class="ls-list">
          @for (s of suspensionRows(); track s.id) {
            <li>
              <strong>{{ s.startDate | appDate: 'date' }} a {{ s.endDate | appDate: 'date' }}</strong>
              <span>{{ s.courtCode ? 'Tribunal ' + s.courtCode : 'Todos os tribunais' }}</span>
              <span class="app-muted">{{ s.reason }}</span>
              @if (canManage()) {
                <button mat-button type="button" (click)="removeSuspension(s)">Remover</button>
              }
            </li>
          } @empty {
            <li class="app-muted">Nenhuma suspensão cadastrada.</li>
          }
        </ul>
      </section>
    }
  `,
  styles: `
    .ls-form {
      margin-bottom: var(--app-gap);
    }
    .ls-grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(min(100%, 280px), 1fr));
      gap: 12px 16px;
      margin-bottom: 8px;
    }
    .ls-head {
      display: flex;
      flex-wrap: wrap;
      justify-content: space-between;
      align-items: center;
      gap: 8px;
    }
    .ls-list {
      margin: 0;
      padding: 0;
      list-style: none;
      li {
        display: flex;
        flex-wrap: wrap;
        align-items: center;
        gap: 4px 12px;
        padding: 8px 0;
        border-bottom: 1px solid var(--app-border);
      }
    }
  `,
})
export class LegalSettingsPage {
  private readonly api = inject(LegalSetupApi);
  private readonly operations = inject(LegalOperationsApi);
  private readonly auth = inject(AuthService);
  private readonly forms = inject(FormDialogService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  protected readonly canManage = computed(() => this.auth.hasPermission(Permission.LEGAL_MANAGE));
  protected readonly saving = signal(false);

  private readonly list = (labels: Record<string, string>) => Object.entries(labels).map(([value, label]) => ({ value, label }));
  protected readonly conduct = this.list(CONDUCT_LABELS);
  protected readonly advancers = this.list(ADVANCER_LABELS);
  protected readonly budget = this.list(BUDGET_LABELS);
  protected readonly prescribed = this.list(PRESCRIBED_LABELS);
  protected readonly hold = this.list(HOLD_LABELS);

  protected readonly settings = rxResource({ stream: () => this.api.settings() });
  protected readonly current = valueOr(this.settings, undefined);
  private readonly suspensions = rxResource({ stream: () => this.operations.suspensions().pipe(catchError(() => of<SuspensionView[]>([]))) });
  protected readonly suspensionRows = valueOr(this.suspensions, []);

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    conductModel: this.fb.control<LegalSettings['conductModel']>('INTERNAL_ONLY'),
    allowedExpenseAdvancers: this.fb.control<string[]>(['CREDITOR'], Validators.minLength(1)),
    budgetEnforcement: this.fb.control<LegalSettings['budgetEnforcement']>('BLOCK'),
    prescribedTitlePolicy: this.fb.control<LegalSettings['prescribedTitlePolicy']>('EXCLUDE'),
    collectionHoldStage: this.fb.control<LegalSettings['collectionHoldStage']>('AUTHORIZATION'),
    alertLevels: ['5, 2, 0', (c: { value: string }) => (parseAlertLevels(c.value) ? null : { levels: true })],
    yearEndRecess: [true],
    removalDeadlineBusinessDays: [5, [Validators.required, Validators.min(1), Validators.max(30)]],
    homologationDeadlineBusinessDays: [5, [Validators.required, Validators.min(1), Validators.max(60)]],
    creditBureauNoticeDays: [10, [Validators.required, Validators.min(1), Validators.max(60)]],
    prescriptionWarningDays: [180, [Validators.required, Validators.min(1), Validators.max(1825)]],
    referralCooldownDays: [180, [Validators.required, Validators.min(0), Validators.max(1825)]],
  });

  constructor() {
    effect(() => {
      const s = this.current();
      untracked(() => {
        if (s) {
          this.form.reset({ ...s, alertLevels: s.deadlineAlertLevels.join(', ') });
        }
        if (!this.canManage()) {
          this.form.disable();
        }
      });
    });
  }

  protected save(): void {
    if (this.form.invalid || this.saving()) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    const { alertLevels, ...rest } = v;
    const request: LegalSettings = { ...rest, deadlineAlertLevels: parseAlertLevels(alertLevels)! };
    this.confirm
      .confirm({
        title: 'Salvar parâmetros do jurídico',
        message: 'As regras passam a valer para os próximos registros; prazos pendentes são recalculados na próxima varredura.',
        confirmLabel: 'Salvar',
        details: [
          { label: 'Condução', value: CONDUCT_LABELS[request.conductModel] },
          { label: 'Adiantam custas', value: request.allowedExpenseAdvancers.map((a) => ADVANCER_LABELS[a]).join(', ') },
          { label: 'Orçamento', value: BUDGET_LABELS[request.budgetEnforcement] },
        ],
      })
      .subscribe(() => {
        this.saving.set(true);
        this.api
          .updateSettings(request)
          .pipe(finalize(() => this.saving.set(false)))
          .subscribe(() => {
            this.notifications.success('Parâmetros salvos.');
            this.settings.reload();
          });
      });
  }

  protected addSuspension(): void {
    this.forms
      .open<SuspensionView>({
        title: 'Nova suspensão forense',
        submitLabel: 'Cadastrar',
        fields: [
          { key: 'startDate', label: 'Início', type: 'date', required: true },
          { key: 'endDate', label: 'Fim', type: 'date', required: true },
          {
            key: 'courtCode',
            label: 'Tribunal (J.TR, opcional)',
            type: 'text',
            maxLength: 5,
            hint: 'Ex.: 8.26 (TJSP). Vazio = todos os tribunais.',
            validate: (v) => (/^\d\.\d{2}$/.test(String(v)) ? null : 'Use o formato J.TR, por exemplo 8.26.'),
          },
          { key: 'reason', label: 'Motivo', type: 'text', required: true, maxLength: 300, wide: true },
        ],
        submit: (v) => this.operations.addSuspension(v),
      })
      .subscribe(() => {
        this.notifications.success('Suspensão cadastrada; prazos pendentes recalculados.');
        this.suspensions.reload();
      });
  }

  protected removeSuspension(s: SuspensionView): void {
    this.confirm
      .confirm({ title: 'Remover suspensão', message: 'Os prazos pendentes do período são recalculados.', confirmLabel: 'Remover', destructive: true, reason: 'required' })
      .subscribe((reason) =>
        this.operations.removeSuspension(s.id, reason).subscribe(() => {
          this.notifications.success('Suspensão removida.');
          this.suspensions.reload();
        }),
      );
  }
}
