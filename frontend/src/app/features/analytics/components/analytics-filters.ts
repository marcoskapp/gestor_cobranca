import { Component, DestroyRef, OnInit, computed, effect, inject, input, output } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { debounceTime } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { fromLocalDate, toLocalDate } from '../../../shared/models/dates';
import { UserLookup } from '../../admin/data/user.lookup';
import { TeamLookup } from '../../collection/data/collection.lookups';
import { CreditorLookup, PortfolioLookup } from '../../organization/data/organization.lookups';
import { PeriodPreset, presetOf, presetRange } from '../data/analytics.filters';
import { CHANNEL_LABELS, GROUP_BY_LABELS } from '../data/analytics.labels';
import { AnalyticsFilter } from '../data/analytics.models';

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

const CHANNELS = ['PHONE', 'WHATSAPP', 'EMAIL', 'SMS', 'PORTAL', 'IN_PERSON'];

/**
 * Barra única de filtros acima dos painéis: período primeiro (atalhos e intervalo personalizado),
 * depois credor, carteira, operador, equipe, canal e agrupamento. Emite o filtro completo; a página
 * grava na URL (compartilhável e preservado ao recarregar).
 */
@Component({
  selector: 'app-analytics-filters',
  imports: [ReactiveFormsModule, MatButtonModule, MatDatepickerModule, MatFormFieldModule, MatIconModule, MatInputModule, MatSelectModule, EntityPicker],
  template: `
    <form class="app-filters af" data-tour="filters" [formGroup]="form" role="search" aria-label="Filtrar painel">
      @if (options().period !== false) {
        <mat-form-field>
          <mat-label>Período</mat-label>
          <mat-select formControlName="preset">
            @for (p of presets; track p.value) {
              <mat-option [value]="p.value">{{ p.label }}</mat-option>
            }
          </mat-select>
        </mat-form-field>
        @if (form.controls.preset.value === 'custom') {
          <mat-form-field class="af__date">
            <mat-label>De</mat-label>
            <input matInput [matDatepicker]="fromPicker" formControlName="from" [max]="form.controls.to.value" />
            <mat-datepicker-toggle matIconSuffix [for]="fromPicker" />
            <mat-datepicker #fromPicker />
          </mat-form-field>
          <mat-form-field class="af__date">
            <mat-label>Até</mat-label>
            <input matInput [matDatepicker]="toPicker" formControlName="to" [min]="form.controls.from.value" />
            <mat-datepicker-toggle matIconSuffix [for]="toPicker" />
            <mat-datepicker #toPicker />
          </mat-form-field>
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
        <mat-form-field>
          <mat-label>Canal</mat-label>
          <mat-select formControlName="channels" multiple>
            @for (c of channels; track c) {
              <mat-option [value]="c">{{ channelLabels[c] }}</mat-option>
            }
          </mat-select>
        </mat-form-field>
      }
      @if (groupOptions().length) {
        <mat-form-field>
          <mat-label>Agrupar por</mat-label>
          <mat-select formControlName="groupBy">
            @for (g of groupOptions(); track g) {
              <mat-option [value]="g">{{ groupLabels[g] }}</mat-option>
            }
          </mat-select>
        </mat-form-field>
      }
      @if (hasFilters()) {
        <div class="app-filters__actions">
          <button mat-button type="button" (click)="clear()">
            <mat-icon aria-hidden="true">filter_alt_off</mat-icon>
            Limpar filtros
          </button>
        </div>
      }
    </form>
  `,
  styles: `
    .af__date { width: 170px !important; }
    .af__picker { width: 220px; }
    @media (max-width: 599.98px) {
      .af__date { width: calc(50% - 6px) !important; }
      .af__picker { width: 100%; }
    }
  `,
})
export class AnalyticsFilters implements OnInit {
  readonly filter = input.required<AnalyticsFilter>();
  readonly options = input<FilterOptions>({ period: true });
  readonly changed = output<AnalyticsFilter>();

  private readonly auth = inject(AuthService);
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
    from: this.fb.control<Date | null>(null),
    to: this.fb.control<Date | null>(null),
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
          from: fromLocalDate(f.from),
          to: fromLocalDate(f.to),
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
        this.form.patchValue({ from: fromLocalDate(range.from), to: fromLocalDate(range.to) }, { emitEvent: false });
      }
    });
    this.form.valueChanges.pipe(debounceTime(300), takeUntilDestroyed(this.destroyRef)).subscribe(() => this.emit());
  }

  protected clear(): void {
    this.form.patchValue({ creditorIds: [], portfolioIds: [], operatorIds: [], teamIds: [], channels: [] });
  }

  private emit(): void {
    const v = this.form.getRawValue();
    const from = toLocalDate(v.from);
    const to = toLocalDate(v.to);
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
