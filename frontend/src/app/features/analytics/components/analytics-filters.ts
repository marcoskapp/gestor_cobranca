import { Component, DestroyRef, OnInit, computed, effect, inject, input, output } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { NgbDateStruct, NgbDropdownModule, NgbInputDatepicker } from '@ng-bootstrap/ng-bootstrap';
import { debounceTime } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { UserLookup } from '../../admin/data/user.lookup';
import { TeamLookup } from '../../collection/data/collection.lookups';
import { CreditorLookup, PortfolioLookup } from '../../organization/data/organization.lookups';
import { PeriodPreset, presetOf, presetRange } from '../data/analytics.filters';
import { CHANNEL_LABELS, GROUP_BY_LABELS } from '../data/analytics.labels';
import { AnalyticsFilter } from '../data/analytics.models';
import { DensityService } from '../../../core/preferences/density.service';
import { Icon } from '../../../shared/components/icon/icon';
import { FORM_FIELD } from '../../../shared/forms/field';

/** Quais filtros a tela oferece (todos os painéis compartilham o mesmo estado na URL). */
export interface FilterOptions {
  period?: boolean;
  creditor?: boolean;
  portfolio?: boolean;
  people?: boolean;
  channel?: boolean;
  groupBy?: readonly string[];
}

const PRESETS: { value: PeriodPreset | 'custom'; label: string }[] = [
  { value: '7d', label: 'Últimos 7 dias' },
  { value: '30d', label: 'Últimos 30 dias' },
  { value: '90d', label: 'Últimos 90 dias' },
  { value: 'month', label: 'Mês atual' },
  { value: 'previous-month', label: 'Mês anterior' },
  { value: 'year', label: 'Ano atual' },
  { value: 'custom', label: 'Personalizado' },
];

const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;
const isoDate = (value: string | null): string | null => (value && ISO_DATE.test(value) ? value : null);

const CHANNELS = ['PHONE', 'WHATSAPP', 'EMAIL', 'SMS', 'PORTAL', 'IN_PERSON'];

/**
 * Barra única de filtros acima dos painéis: período primeiro (atalhos e intervalo personalizado),
 * depois credor, carteira, operador, equipe, canal e agrupamento. Emite o filtro completo; a página
 * grava na URL (compartilhável e preservado ao recarregar).
 */
@Component({
  selector: 'app-analytics-filters',
  imports: [ReactiveFormsModule, ...FORM_FIELD, NgbInputDatepicker, NgbDropdownModule, EntityPicker, Icon],
  template: `
    <form class="app-filters af" data-tour="filters" [formGroup]="form" role="search" aria-label="Filtrar painel">
      @if (options().period !== false) {
        <app-field label="Período">
          <select appInput formControlName="preset">
            @for (p of presets; track p.value) {
              <option [ngValue]="p.value">{{ p.label }}</option>
            }
          </select>
        </app-field>
        @if (form.controls.preset.value === 'custom') {
          <app-field class="af__date" label="De">
            <div class="input-group">
              <input appInput formControlName="from" [maxDate]="bound(form.controls.to.value, maxDate)" ngbDatepicker #fromPicker="ngbDatepicker" container="body" placeholder="dd/mm/aaaa" autocomplete="off" />
              <button class="btn btn-outline-secondary" type="button" (click)="fromPicker.toggle()" aria-label="Abrir calendário da data inicial">
                <app-icon name="calendar-event" />
              </button>
            </div>
          </app-field>
          <app-field class="af__date" label="Até">
            <div class="input-group">
              <input appInput formControlName="to" [minDate]="bound(form.controls.from.value, minDate)" ngbDatepicker #toPicker="ngbDatepicker" container="body" placeholder="dd/mm/aaaa" autocomplete="off" />
              <button class="btn btn-outline-secondary" type="button" (click)="toPicker.toggle()" aria-label="Abrir calendário da data final">
                <app-icon name="calendar-event" />
              </button>
            </div>
          </app-field>
        }
      }
      @if (options().creditor && canCreditors()) {
        <div class="af__picker">
          <app-entity-picker [control]="form.controls.creditorIds" [source]="creditorSource" label="Credor" />
        </div>
      }
      @if (options().portfolio && canPortfolios()) {
        <div class="af__picker">
          <app-entity-picker [control]="form.controls.portfolioIds" [source]="portfolioSource" label="Carteira" />
        </div>
      }
      @if (options().people && canUsers()) {
        <div class="af__picker">
          <app-entity-picker [control]="form.controls.operatorIds" [source]="userSource" label="Operador" />
        </div>
      }
      @if (options().people && canTeams()) {
        <div class="af__picker">
          <app-entity-picker [control]="form.controls.teamIds" [source]="teamSource" label="Equipe" />
        </div>
      }
      @if (options().channel) {
        <div class="af__channels">
          <span class="form-label d-block" id="af-channel-label">Canal</span>
          <div ngbDropdown autoClose="outside" class="d-block">
            <button
              class="form-select text-start text-truncate"
              [class.form-select-sm]="density.compact()"
              type="button"
              ngbDropdownToggle
              aria-labelledby="af-channel-label af-channel-value"
            >
              <span id="af-channel-value">{{ channelSummary() }}</span>
            </button>
            <div ngbDropdownMenu class="shadow px-3 py-2" role="group" aria-labelledby="af-channel-label">
              @for (c of channels; track c) {
                <div class="form-check my-1">
                  <input
                    class="form-check-input"
                    type="checkbox"
                    [id]="'af-channel-' + c"
                    [checked]="form.controls.channels.value.includes(c)"
                    #channelBox
                    (change)="toggleChannel(c, channelBox.checked)"
                  />
                  <label class="form-check-label text-nowrap" [for]="'af-channel-' + c">{{ channelLabels[c] }}</label>
                </div>
              }
            </div>
          </div>
        </div>
      }
      @if (groupOptions().length) {
        <app-field label="Agrupar por">
          <select appInput formControlName="groupBy">
            @for (g of groupOptions(); track g) {
              <option [ngValue]="g">{{ groupLabels[g] }}</option>
            }
          </select>
        </app-field>
      }
      @if (hasFilters()) {
        <div class="app-filters__actions">
          <button class="btn btn-link" type="button" (click)="clear()">
            <app-icon name="funnel" />
            Limpar filtros
          </button>
        </div>
      }
    </form>
  `,
  styles: `
    .af__date { width: 170px !important; }
    .af__picker,
    .af__channels { width: 220px; }
    @media (max-width: 599.98px) {
      .af__date { width: calc(50% - 6px) !important; }
      .af__picker,
      .af__channels { width: 100%; }
    }
  `,
})
export class AnalyticsFilters implements OnInit {
  readonly filter = input.required<AnalyticsFilter>();
  readonly options = input<FilterOptions>({ period: true });
  readonly changed = output<AnalyticsFilter>();

  private readonly auth = inject(AuthService);
  protected readonly density = inject(DensityService);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly creditorSource = inject(CreditorLookup);
  protected readonly userSource = inject(UserLookup);
  protected readonly teamSource = inject(TeamLookup);
  protected readonly portfolioSource = inject(PortfolioLookup).source(() => this.form.controls.creditorIds.value[0] ?? null);
  protected readonly presets = PRESETS;
  protected readonly channels = CHANNELS;
  protected readonly channelLabels = CHANNEL_LABELS;
  protected readonly groupLabels = GROUP_BY_LABELS;

  protected readonly canCreditors = computed(() => this.auth.hasPermission(Permission.CREDITOR_VIEW));
  protected readonly canPortfolios = computed(() => this.auth.hasPermission(Permission.PORTFOLIO_VIEW));
  /** Seletor de pessoas só para quem pode ver usuários (operador vê apenas o próprio desempenho). */
  protected readonly canUsers = computed(() => this.auth.hasPermission(Permission.USER_VIEW));
  protected readonly canTeams = computed(() =>
    this.auth.hasAnyPermission([Permission.TEAM_MANAGE, Permission.PERFORMANCE_VIEW_TEAM, Permission.PERFORMANCE_VIEW_ALL]),
  );
  protected readonly groupOptions = computed(() => this.options().groupBy ?? []);

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    preset: this.fb.control<PeriodPreset | 'custom'>('30d'),
    // modelo ISO (yyyy-MM-dd) do ngbDatepicker; texto não reconhecido fica como digitado
    from: this.fb.control<string | null>(null),
    to: this.fb.control<string | null>(null),
    creditorIds: this.fb.control<string[]>([]),
    portfolioIds: this.fb.control<string[]>([]),
    operatorIds: this.fb.control<string[]>([]),
    teamIds: this.fb.control<string[]>([]),
    channels: this.fb.control<string[]>([]),
    groupBy: this.fb.control<string | null>(null),
  });

  protected readonly hasFilters = computed(() => {
    const f = this.filter();
    return f.creditorIds.length + f.portfolioIds.length + f.operatorIds.length + f.teamIds.length + f.channels.length > 0;
  });

  constructor() {
    // URL → formulário (navegação, voltar/avançar), sem reemitir
    effect(() => {
      const f = this.filter();
      this.form.patchValue(
        {
          preset: presetOf(f.from, f.to, new Date()),
          from: f.from,
          to: f.to,
          creditorIds: f.creditorIds,
          portfolioIds: f.portfolioIds,
          operatorIds: f.operatorIds,
          teamIds: f.teamIds,
          channels: f.channels,
          groupBy: f.groupBy,
        },
        { emitEvent: false },
      );
    });
  }

  ngOnInit(): void {
    this.form.controls.preset.valueChanges.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((preset) => {
      if (preset !== 'custom') {
        const range = presetRange(preset, new Date());
        this.form.patchValue({ from: range.from, to: range.to }, { emitEvent: false });
      }
    });
    this.form.valueChanges.pipe(debounceTime(300), takeUntilDestroyed(this.destroyRef)).subscribe(() => this.emit());
  }

  /** Rótulo do seletor de canais (como o select múltiplo: os escolhidos, ou "Todos"). */
  protected channelSummary(): string {
    const selected = this.form.controls.channels.value;
    return selected.length ? selected.map((c) => this.channelLabels[c] ?? c).join(', ') : 'Todos';
  }

  /** Limites padrão do calendário quando a outra data do intervalo está vazia ou inválida. */
  protected readonly minDate: NgbDateStruct = { year: 1900, month: 1, day: 1 };
  protected readonly maxDate: NgbDateStruct = { year: 2100, month: 12, day: 31 };

  /** Limite do calendário (`NgbDateStruct`) a partir da outra data do intervalo. */
  protected bound(value: string | null, fallback: NgbDateStruct): NgbDateStruct {
    const iso = isoDate(value);
    if (!iso) {
      return fallback;
    }
    const [year, month, day] = iso.split('-').map(Number);
    return { year, month, day };
  }

  protected toggleChannel(channel: string, checked: boolean): void {
    const others = this.form.controls.channels.value.filter((c) => c !== channel);
    this.form.controls.channels.setValue(checked ? [...others, channel] : others);
  }

  protected clear(): void {
    this.form.patchValue({ creditorIds: [], portfolioIds: [], operatorIds: [], teamIds: [], channels: [] });
  }

  private emit(): void {
    const v = this.form.getRawValue();
    const from = isoDate(v.from);
    const to = isoDate(v.to);
    if (!from || !to || to < from) {
      return;
    }
    this.changed.emit({
      from,
      to,
      creditorIds: v.creditorIds,
      portfolioIds: v.portfolioIds,
      operatorIds: v.operatorIds,
      teamIds: v.teamIds,
      channels: v.channels,
      groupBy: v.groupBy,
    });
  }
}
