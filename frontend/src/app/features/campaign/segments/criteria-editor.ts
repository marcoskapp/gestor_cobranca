import { Component, DestroyRef, computed, effect, inject, input, linkedSignal, output, untracked } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatMenuModule } from '@angular/material/menu';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatTooltipModule } from '@angular/material/tooltip';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { statusOptions } from '../../../shared/status/status';
import { PortfolioLookup } from '../../organization/data/organization.lookups';
import { CHANNEL_LABELS, OWNER_TYPE_LABELS } from '../data/campaign.labels';
import { SegmentCriteria } from '../data/campaign.models';
import { CASE_STATUSES, CONTACT_CHANNELS, CRITERIA_FILTERS, CriteriaFilter, OWNER_TYPES, activeFilterKeys, clearFilter } from '../data/campaign.rules';

/**
 * Construtor de filtros combináveis (todos por E) sobre `SegmentCriteria`. Edita o objeto
 * recebido no lugar e avisa a tela a cada alteração (`edited`), que revalida e atualiza a
 * contagem prévia. Usado no segmento e na condição de uma etapa da régua.
 */
@Component({
  selector: 'app-criteria-editor',
  imports: [FormsModule, MatButtonModule, MatFormFieldModule, MatIconModule, MatInputModule, MatMenuModule, MatSelectModule, MatSlideToggleModule, MatTooltipModule, EntityPicker],
  template: `
    @let c = criteria();
    @let ro = readonly();
    <div class="ce-head">
      <mat-slide-toggle [(ngModel)]="c.eligibleOnly" (ngModelChange)="changed()" [disabled]="ro">Somente casos elegíveis</mat-slide-toggle>
      <span class="app-muted ce-small">{{ c.eligibleOnly === false ? 'Inclui casos bloqueados (ex.: contestação, carteira suspensa): use só para comunicados.' : 'Regra RB02: casos bloqueados ficam fora.' }}</span>
    </div>

    <ul class="ce-list" [attr.aria-label]="label()">
      @for (f of shownFilters(); track f.key; let first = $first) {
        <li class="ce-filter">
          <span class="ce-and" aria-hidden="true">{{ first ? 'Onde' : 'E' }}</span>
          <div class="ce-body">
            <span class="ce-label" [id]="id + f.key">{{ f.label }}</span>
            <div class="ce-fields">
              @switch (f.kind) {
                @case ('portfolios') {
                  <div class="ce-wide">
                    <app-entity-picker [control]="portfolios" [source]="portfolioSource" label="Carteiras" placeholder="Pesquisar carteira…" />
                  </div>
                }
                @case ('statuses') {
                  <mat-form-field class="app-compact ce-wide">
                    <mat-label>Situações</mat-label>
                    <mat-select multiple [(ngModel)]="c.caseStatuses" (ngModelChange)="changed()" [disabled]="ro">
                      @for (s of caseStatuses; track s.value) {
                        <mat-option [value]="s.value">{{ s.label }}</mat-option>
                      }
                    </mat-select>
                  </mat-form-field>
                }
                @case ('owners') {
                  <mat-form-field class="app-compact ce-wide">
                    <mat-label>Dono</mat-label>
                    <mat-select multiple [(ngModel)]="c.ownerTypes" (ngModelChange)="changed()" [disabled]="ro">
                      @for (o of owners; track o) {
                        <mat-option [value]="o">{{ ownerLabels[o] }}</mat-option>
                      }
                    </mat-select>
                  </mat-form-field>
                }
                @case ('channel') {
                  <mat-form-field class="app-compact">
                    <mat-label>Canal</mat-label>
                    <mat-select [(ngModel)]="c.contactableChannel" (ngModelChange)="changed()" [disabled]="ro">
                      @for (ch of channels; track ch) {
                        <mat-option [value]="ch">{{ channelLabels[ch] }}</mat-option>
                      }
                    </mat-select>
                  </mat-form-field>
                }
                @case ('yes-no') {
                  <mat-form-field class="app-compact ce-wide">
                    <mat-label>{{ f.label }}</mat-label>
                    <mat-select [ngModel]="boolValue(f)" (ngModelChange)="setBool(f, $event)" [disabled]="ro">
                      <mat-option [value]="true">{{ f.yes }}</mat-option>
                      <mat-option [value]="false">{{ f.no }}</mat-option>
                    </mat-select>
                  </mat-form-field>
                }
                @case ('int') {
                  <mat-form-field class="app-compact">
                    <mat-label>Dias</mat-label>
                    <input matInput type="number" min="0" [max]="f.max ?? 36500" step="1" [ngModel]="num(f.fields[0])" (ngModelChange)="setNum(f.fields[0], $event)" [disabled]="ro" />
                  </mat-form-field>
                }
                @default {
                  <mat-form-field class="app-compact">
                    <mat-label>Mínimo</mat-label>
                    <input matInput type="number" min="0" [max]="f.max ?? null" [step]="f.kind === 'money-range' ? 0.01 : 1" [ngModel]="num(f.fields[0])" (ngModelChange)="setNum(f.fields[0], $event)" [disabled]="ro" />
                  </mat-form-field>
                  <mat-form-field class="app-compact">
                    <mat-label>Máximo</mat-label>
                    <input matInput type="number" min="0" [max]="f.max ?? null" [step]="f.kind === 'money-range' ? 0.01 : 1" [ngModel]="num(f.fields[1])" (ngModelChange)="setNum(f.fields[1], $event)" [disabled]="ro" />
                  </mat-form-field>
                }
              }
            </div>
            @if (f.hint) {
              <span class="app-muted ce-small">{{ f.hint }}</span>
            }
          </div>
          @if (!ro) {
            <button mat-icon-button type="button" (click)="remove(f)" [attr.aria-label]="'Remover filtro ' + f.label" matTooltip="Remover filtro">
              <mat-icon aria-hidden="true">close</mat-icon>
            </button>
          }
        </li>
      } @empty {
        <li class="app-muted ce-small ce-none">Nenhum filtro além da elegibilidade: todos os casos {{ c.eligibleOnly === false ? '' : 'elegíveis ' }}entram.</li>
      }
    </ul>

    @if (!ro && hiddenFilters().length) {
      <button mat-stroked-button type="button" [matMenuTriggerFor]="menu" data-tour="segment-add-filter">
        <mat-icon aria-hidden="true">add</mat-icon>
        Adicionar filtro
      </button>
      <mat-menu #menu="matMenu">
        @for (g of groups(); track g.group) {
          <div class="ce-menu-group" role="presentation">{{ g.group }}</div>
          @for (f of g.filters; track f.key) {
            <button mat-menu-item type="button" (click)="add(f)">{{ f.label }}</button>
          }
        }
      </mat-menu>
    }
  `,
  styles: `
    .ce-head {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 4px 12px;
      margin-bottom: 8px;
    }
    .ce-small {
      font-size: var(--app-text-xs);
    }
    .ce-list {
      display: grid;
      gap: 8px;
      margin: 0 0 8px;
      padding: 0;
      list-style: none;
    }
    .ce-filter {
      display: flex;
      align-items: flex-start;
      gap: 8px;
      padding: 8px 4px 0 8px;
      border: 1px solid var(--app-border);
      border-radius: var(--app-radius-md);
      background: var(--app-surface);
    }
    .ce-and {
      flex: 0 0 44px;
      padding-top: 4px;
      font-size: var(--app-text-xs);
      font-weight: 700;
      letter-spacing: 0.06em;
      text-transform: uppercase;
      color: var(--mat-sys-primary);
    }
    .ce-body {
      display: flex;
      flex: 1 1 auto;
      flex-direction: column;
      min-width: 0;
      padding-bottom: 6px;
    }
    .ce-label {
      margin-bottom: 4px;
      font-size: var(--app-text-sm);
      font-weight: 600;
    }
    .ce-fields {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
      mat-form-field {
        flex: 0 1 160px;
        min-width: 120px;
      }
      .ce-wide {
        flex: 1 1 260px;
      }
    }
    .ce-none {
      padding: 4px 0;
    }
    .ce-menu-group {
      padding: 8px 16px 4px;
      font-size: var(--app-text-xs);
      font-weight: 600;
      letter-spacing: 0.06em;
      text-transform: uppercase;
      color: var(--mat-sys-on-surface-variant);
    }
    @media (max-width: 599.98px) {
      .ce-and {
        display: none;
      }
    }
  `,
})
export class CriteriaEditor {
  readonly criteria = input.required<SegmentCriteria>();
  readonly readonly = input(false);
  /** Credor do segmento/campanha: restringe a busca de carteiras. */
  readonly creditorId = input<string | null>(null);
  readonly label = input('Filtros combinados');
  readonly edited = output<void>();

  private static seq = 0;
  protected readonly id = `ce-${++CriteriaEditor.seq}-`;
  protected readonly caseStatuses = statusOptions('case').filter((s) => (CASE_STATUSES as readonly string[]).includes(s.value));
  protected readonly owners = OWNER_TYPES;
  protected readonly ownerLabels = OWNER_TYPE_LABELS;
  protected readonly channels = CONTACT_CHANNELS;
  protected readonly channelLabels = CHANNEL_LABELS;
  protected readonly portfolioSource = inject(PortfolioLookup).source(() => this.creditorId());
  protected readonly portfolios = new FormControl<string[]>([], { nonNullable: true });

  /** Filtros exibidos: os que têm valor ao abrir e os que a pessoa acrescentou. */
  private readonly shown = linkedSignal<SegmentCriteria, ReadonlySet<string>>({
    source: this.criteria,
    computation: (c) => new Set(activeFilterKeys(c)),
  });
  protected readonly shownFilters = computed(() => CRITERIA_FILTERS.filter((f) => this.shown().has(f.key)));
  protected readonly hiddenFilters = computed(() => CRITERIA_FILTERS.filter((f) => !this.shown().has(f.key)));
  protected readonly groups = computed(() => {
    const groups: { group: string; filters: CriteriaFilter[] }[] = [];
    for (const f of this.hiddenFilters()) {
      const g = groups.find((x) => x.group === f.group);
      if (g) g.filters.push(f);
      else groups.push({ group: f.group, filters: [f] });
    }
    return groups;
  });

  constructor() {
    effect(() => {
      const c = this.criteria();
      const ro = this.readonly();
      untracked(() => {
        this.portfolios.setValue([...(c.portfolioIds ?? [])], { emitEvent: false });
        if (ro) this.portfolios.disable({ emitEvent: false });
        else this.portfolios.enable({ emitEvent: false });
      });
    });
    this.portfolios.valueChanges.pipe(takeUntilDestroyed(inject(DestroyRef))).subscribe((ids) => {
      this.criteria().portfolioIds = ids.length ? [...ids] : null;
      this.changed();
    });
  }

  protected changed(): void {
    this.edited.emit();
  }

  protected num(field: keyof SegmentCriteria): number | null {
    return this.criteria()[field] as number | null;
  }

  protected setNum(field: keyof SegmentCriteria, value: number | string | null): void {
    const n = value === '' || value === null || value === undefined ? null : Number(value);
    (this.criteria() as unknown as Record<string, unknown>)[field] = n === null || Number.isNaN(n) ? null : n;
    this.changed();
  }

  protected boolValue(f: CriteriaFilter): boolean | null {
    return this.criteria()[f.fields[0]] as boolean | null;
  }

  protected setBool(f: CriteriaFilter, value: boolean): void {
    (this.criteria() as unknown as Record<string, unknown>)[f.fields[0]] = value;
    this.changed();
  }

  protected add(f: CriteriaFilter): void {
    this.shown.update((s) => new Set([...s, f.key]));
    if (f.kind === 'yes-no') {
      // sim/não sem escolha não filtra nada: começa pela opção "não" (ex.: sem acordo em vigor)
      (this.criteria() as unknown as Record<string, unknown>)[f.fields[0]] = false;
      this.changed();
    }
  }

  protected remove(f: CriteriaFilter): void {
    clearFilter(this.criteria(), f.key);
    if (f.kind === 'portfolios') {
      this.portfolios.setValue([], { emitEvent: false });
    }
    this.shown.update((s) => new Set([...s].filter((k) => k !== f.key)));
    this.changed();
  }
}
