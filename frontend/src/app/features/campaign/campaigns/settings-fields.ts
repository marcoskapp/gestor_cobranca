import { Component, computed, inject, input } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { map } from 'rxjs';
import { valueOr } from '../../../shared/data/resource-value';
import { FORM_FIELD } from '../../../shared/forms/field';
import { AppValidators } from '../../../shared/validation/validators';
import { SegmentsApi } from '../data/campaign.api';
import { PURPOSE_HINTS, PURPOSE_LABELS } from '../data/campaign.labels';
import { CampaignPurpose, CampaignSettings, CampaignView } from '../data/campaign.models';

const GROUP = /^[A-Z][A-Z0-9_]{0,59}$/;
const PURPOSES = Object.keys(PURPOSE_LABELS) as CampaignPurpose[];
let nextId = 0;

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
  imports: [ReactiveFormsModule, ...FORM_FIELD],
  template: `
    @let f = form();
    <div [formGroup]="f" class="row g-3">
      <app-field class="col-md-6" label="Nome">
        <input appInput formControlName="name" maxlength="120" required />
      </app-field>
      <app-field class="col-md-6" label="Segmento" [hint]="segmentLocked() ? 'Pause a campanha para trocar o segmento.' : 'Segmentos ativos do credor ou da assessoria.'">
        <select appInput formControlName="segmentId" required>
          @for (s of segments(); track s.id; let first = $first) {
            @if (first) {
              <option value="" disabled>Selecione</option>
            }
            <option [value]="s.id">{{ s.name }}{{ s.creditorId ? '' : ' (assessoria)' }}{{ s.active ? '' : ' — inativo' }}</option>
          } @empty {
            <option value="" disabled>{{ creditorId() ? 'Nenhum segmento ativo para o credor' : 'Escolha o credor' }}</option>
          }
        </select>
      </app-field>
      <app-field class="col-12" label="Descrição">
        <textarea appInput formControlName="description" rows="2" maxlength="500"></textarea>
      </app-field>

      <p class="app-section-title col-12 mb-0">Finalidade e concorrência</p>
      <app-field class="col-md-6 col-lg-4" label="Finalidade" [hint]="purposeHints[f.controls.purpose.value]">
        <select appInput formControlName="purpose">
          @for (p of purposes; track p) {
            <option [ngValue]="p">{{ purposeLabels[p] }}</option>
          }
        </select>
      </app-field>
      <app-field class="col-md-6 col-lg-3" label="Prioridade" hint="0 a 1000; maior vence no grupo.">
        <input appInput type="number" formControlName="priority" min="0" max="1000" step="1" required />
      </app-field>
      <app-field
        class="col-lg-5"
        label="Grupo de exclusividade"
        hint="Campanhas do mesmo grupo não alcançam o mesmo devedor ao mesmo tempo. Vazio = convive com todas."
        error="Letras maiúsculas, dígitos e _, começando por letra."
      >
        <input appInput formControlName="exclusivityGroup" maxlength="60" spellcheck="false" autocomplete="off" placeholder="Ex.: COBRANCA_ATIVA" />
      </app-field>

      <p class="app-section-title col-12 mb-0">Inscrição, pausa e atribuição</p>
      <div class="col-12 d-grid gap-2">
        <div class="form-check form-switch">
          <input class="form-check-input" type="checkbox" role="switch" [id]="ids + '-exit'" formControlName="exitWhenOutOfSegment" />
          <label class="form-check-label" [for]="ids + '-exit'">Encerrar a inscrição quando o caso sair do segmento</label>
        </div>
        <div class="form-check form-switch">
          <input class="form-check-input" type="checkbox" role="switch" [id]="ids + '-reenroll'" formControlName="reenroll" />
          <label class="form-check-label" [for]="ids + '-reenroll'">Reinscrever o caso depois de um tempo</label>
        </div>
        <div class="form-check form-switch">
          <input class="form-check-input" type="checkbox" role="switch" [id]="ids + '-resume'" formControlName="paymentResume" />
          <label class="form-check-label" [for]="ids + '-resume'">Retomar sozinha após pagamento confirmado</label>
        </div>
      </div>
      @if (f.controls.reenroll.value) {
        <app-field class="col-sm-6 col-lg-3" label="Reinscrever após (dias)" hint="Desde o fim da inscrição anterior.">
          <input appInput type="number" formControlName="reenrollAfterDays" min="1" max="3650" step="1" />
        </app-field>
      }
      @if (f.controls.paymentResume.value) {
        <app-field class="col-sm-6 col-lg-3" label="Retomar após (dias)" hint="Sem retomada automática, só manual.">
          <input appInput type="number" formControlName="paymentPauseDays" min="1" max="180" step="1" />
        </app-field>
      }
      <app-field class="col-sm-6 col-lg-3" label="Janela de atribuição (dias)" hint="Resultados após o fim da inscrição que ainda contam.">
        <input appInput type="number" formControlName="attributionWindowDays" min="0" max="90" step="1" required />
      </app-field>
      <app-field class="col-sm-6 col-lg-3" label="Adiamento máximo (horas)" hint="Fora do horário ou acima do limite, a etapa espera até aqui; depois é pulada.">
        <input appInput type="number" formControlName="maxDeferralHours" min="0" max="168" step="1" required />
      </app-field>
    </div>
  `,
})
export class CampaignSettingsFields {
  readonly form = input.required<SettingsForm>();
  readonly creditorId = input<string | null>(null);
  /** Segmento atual (mantido na lista mesmo inativo). */
  readonly currentSegmentId = input<string | null>(null);
  readonly segmentLocked = input(false);

  /** Prefixo dos ids das chaves (o formulário aparece no diálogo de criação e na visão geral). */
  protected readonly ids = `sf-${++nextId}`;
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
