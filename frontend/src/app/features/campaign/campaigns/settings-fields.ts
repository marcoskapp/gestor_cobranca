import { Component, computed, inject, input } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { map } from 'rxjs';
import { valueOr } from '../../../shared/data/resource-value';
import { AppValidators } from '../../../shared/validation/validators';
import { SegmentsApi } from '../data/campaign.api';
import { PURPOSE_HINTS, PURPOSE_LABELS } from '../data/campaign.labels';
import { CampaignPurpose, CampaignSettings, CampaignView } from '../data/campaign.models';

const GROUP = /^[A-Z][A-Z0-9_]{0,59}$/;
const PURPOSES = Object.keys(PURPOSE_LABELS) as CampaignPurpose[];

/** Formulário dos parâmetros da campanha (`SettingsRequest`), com os limites de `Campaign.apply`. */
export function settingsForm(fb: FormBuilder) {
  const f = fb.nonNullable;
  return f.group({
    name: ['', [AppValidators.notBlank, Validators.maxLength(120)]],
    description: ['', [Validators.maxLength(500)]],
    segmentId: ['', [AppValidators.notBlank]],
    purpose: f.control<CampaignPurpose>('EXTRAJUDICIAL_COLLECTION'),
    priority: f.control<number | null>(100, [Validators.required, Validators.min(0), Validators.max(1000)]),
    exclusivityGroup: ['', [Validators.pattern(GROUP)]],
    reenroll: [false],
    reenrollAfterDays: f.control<number | null>(90, [Validators.min(1), Validators.max(3650)]),
    attributionWindowDays: f.control<number | null>(7, [Validators.required, Validators.min(0), Validators.max(90)]),
    paymentResume: [false],
    paymentPauseDays: f.control<number | null>(30, [Validators.min(1), Validators.max(180)]),
    maxDeferralHours: f.control<number | null>(24, [Validators.required, Validators.min(0), Validators.max(168)]),
    exitWhenOutOfSegment: [true],
  });
}

export type SettingsForm = ReturnType<typeof settingsForm>;

export function patchSettings(form: SettingsForm, c: CampaignView): void {
  form.reset({
    name: c.name,
    description: c.description ?? '',
    segmentId: c.segmentId,
    purpose: c.purpose,
    priority: c.priority,
    exclusivityGroup: c.exclusivityGroup ?? '',
    reenroll: c.reenrollAfterDays !== null,
    reenrollAfterDays: c.reenrollAfterDays ?? 90,
    attributionWindowDays: c.attributionWindowDays,
    paymentResume: c.paymentPauseDays !== null,
    paymentPauseDays: c.paymentPauseDays ?? 30,
    maxDeferralHours: c.maxDeferralHours,
    exitWhenOutOfSegment: c.exitWhenOutOfSegment,
  });
}

export function toSettings(form: SettingsForm): CampaignSettings {
  const v = form.getRawValue();
  return {
    name: v.name.trim(),
    description: v.description.trim() || null,
    segmentId: v.segmentId,
    purpose: v.purpose,
    priority: v.priority,
    exclusivityGroup: v.exclusivityGroup.trim().toUpperCase() || null,
    reenrollAfterDays: v.reenroll ? v.reenrollAfterDays : null,
    attributionWindowDays: v.attributionWindowDays,
    paymentPauseDays: v.paymentResume ? v.paymentPauseDays : null,
    maxDeferralHours: v.maxDeferralHours,
    exitWhenOutOfSegment: v.exitWhenOutOfSegment,
  };
}

/**
 * Campos dos parâmetros da campanha: segmento (do credor ou da assessoria), finalidade, prioridade
 * e grupo de exclusividade, reinscrição, atribuição, pausa por pagamento e adiamento pelo porteiro.
 */
@Component({
  selector: 'app-campaign-settings-fields',
  imports: [ReactiveFormsModule, MatFormFieldModule, MatInputModule, MatSelectModule, MatSlideToggleModule],
  template: `
    @let f = form();
    <div [formGroup]="f" class="sf">
      <div class="sf-row">
        <mat-form-field class="sf-grow">
          <mat-label>Nome</mat-label>
          <input matInput formControlName="name" maxlength="120" required />
        </mat-form-field>
        <mat-form-field class="sf-grow">
          <mat-label>Segmento</mat-label>
          <mat-select formControlName="segmentId" required>
            @for (s of segments(); track s.id) {
              <mat-option [value]="s.id">{{ s.name }}{{ s.creditorId ? '' : ' (assessoria)' }}{{ s.active ? '' : ' — inativo' }}</mat-option>
            } @empty {
              <mat-option disabled>{{ creditorId() ? 'Nenhum segmento ativo para o credor' : 'Escolha o credor' }}</mat-option>
            }
          </mat-select>
          @if (segmentLocked()) {
            <mat-hint>Pause a campanha para trocar o segmento.</mat-hint>
          } @else {
            <mat-hint>Segmentos ativos do credor ou da assessoria.</mat-hint>
          }
        </mat-form-field>
      </div>
      <mat-form-field class="app-full-width">
        <mat-label>Descrição</mat-label>
        <textarea matInput formControlName="description" rows="2" maxlength="500"></textarea>
      </mat-form-field>

      <p class="app-section-title">Finalidade e concorrência</p>
      <div class="sf-row">
        <mat-form-field class="sf-grow">
          <mat-label>Finalidade</mat-label>
          <mat-select formControlName="purpose">
            @for (p of purposes; track p) {
              <mat-option [value]="p">{{ purposeLabels[p] }}</mat-option>
            }
          </mat-select>
          <mat-hint>{{ purposeHints[f.controls.purpose.value] }}</mat-hint>
        </mat-form-field>
        <mat-form-field class="sf-num">
          <mat-label>Prioridade</mat-label>
          <input matInput type="number" formControlName="priority" min="0" max="1000" step="1" required />
          <mat-hint>0 a 1000; maior vence no grupo.</mat-hint>
        </mat-form-field>
        <mat-form-field class="sf-grow">
          <mat-label>Grupo de exclusividade</mat-label>
          <input matInput formControlName="exclusivityGroup" maxlength="60" spellcheck="false" autocomplete="off" placeholder="Ex.: COBRANCA_ATIVA" />
          <mat-hint>Campanhas do mesmo grupo não alcançam o mesmo devedor ao mesmo tempo. Vazio = convive com todas.</mat-hint>
          @if (f.controls.exclusivityGroup.invalid) {
            <mat-error>Letras maiúsculas, dígitos e _, começando por letra.</mat-error>
          }
        </mat-form-field>
      </div>

      <p class="app-section-title">Inscrição, pausa e atribuição</p>
      <div class="sf-row sf-toggles">
        <mat-slide-toggle formControlName="exitWhenOutOfSegment">Encerrar a inscrição quando o caso sair do segmento</mat-slide-toggle>
        <mat-slide-toggle formControlName="reenroll">Reinscrever o caso depois de um tempo</mat-slide-toggle>
        <mat-slide-toggle formControlName="paymentResume">Retomar sozinha após pagamento confirmado</mat-slide-toggle>
      </div>
      <div class="sf-row">
        @if (f.controls.reenroll.value) {
          <mat-form-field class="sf-num">
            <mat-label>Reinscrever após (dias)</mat-label>
            <input matInput type="number" formControlName="reenrollAfterDays" min="1" max="3650" step="1" />
            <mat-hint>Desde o fim da inscrição anterior.</mat-hint>
          </mat-form-field>
        }
        @if (f.controls.paymentResume.value) {
          <mat-form-field class="sf-num">
            <mat-label>Retomar após (dias)</mat-label>
            <input matInput type="number" formControlName="paymentPauseDays" min="1" max="180" step="1" />
            <mat-hint>Sem retomada automática, só manual.</mat-hint>
          </mat-form-field>
        }
        <mat-form-field class="sf-num">
          <mat-label>Janela de atribuição (dias)</mat-label>
          <input matInput type="number" formControlName="attributionWindowDays" min="0" max="90" step="1" required />
          <mat-hint>Resultados após o fim da inscrição que ainda contam.</mat-hint>
        </mat-form-field>
        <mat-form-field class="sf-num">
          <mat-label>Adiamento máximo (horas)</mat-label>
          <input matInput type="number" formControlName="maxDeferralHours" min="0" max="168" step="1" required />
          <mat-hint>Fora do horário ou acima do limite, a etapa espera até aqui; depois é pulada.</mat-hint>
        </mat-form-field>
      </div>
    </div>
  `,
  styles: `
    .sf-row {
      display: flex;
      flex-wrap: wrap;
      gap: 4px 12px;
    }
    .sf-grow {
      flex: 1 1 240px;
      min-width: 0;
    }
    .sf-num {
      flex: 0 1 220px;
      min-width: 160px;
    }
    .sf-toggles {
      flex-direction: column;
      gap: 8px;
      margin-bottom: 12px;
    }
    mat-form-field {
      margin-bottom: 8px;
    }
  `,
})
export class CampaignSettingsFields {
  readonly form = input.required<SettingsForm>();
  readonly creditorId = input<string | null>(null);
  /** Segmento atual (mantido na lista mesmo inativo). */
  readonly currentSegmentId = input<string | null>(null);
  readonly segmentLocked = input(false);

  protected readonly purposes = PURPOSES;
  protected readonly purposeLabels = PURPOSE_LABELS;
  protected readonly purposeHints = PURPOSE_HINTS;

  private readonly api = inject(SegmentsApi);
  private readonly segmentList = rxResource({
    params: () => this.creditorId() ?? undefined,
    stream: ({ params }) =>
      this.api.search({ creditorId: null, active: null }, { page: 0, size: 200 }).pipe(map((page) => page.content.filter((s) => s.creditorId === null || s.creditorId === params))),
  });
  private readonly all = valueOr(this.segmentList, []);
  protected readonly segments = computed(() => this.all().filter((s) => s.active || s.id === this.currentSegmentId()));
}
