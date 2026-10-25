import { Component, DestroyRef, computed, effect, inject, input, linkedSignal, output, untracked } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, FormsModule } from '@angular/forms';
import { NgbDropdownModule, NgbTooltip } from '@ng-bootstrap/ng-bootstrap';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { Icon } from '../../../shared/components/icon/icon';
import { FORM_FIELD } from '../../../shared/forms/field';
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
  imports: [FormsModule, NgbDropdownModule, NgbTooltip, ...FORM_FIELD, EntityPicker, Icon],
  template: `
    @let c = criteria();
    @let ro = readonly();
    <div class="d-flex flex-wrap align-items-center column-gap-3 row-gap-1 mb-2">
      <div class="form-check form-switch mb-0">
        <input class="form-check-input" type="checkbox" role="switch" [id]="id + 'eligible'" [(ngModel)]="c.eligibleOnly" (ngModelChange)="changed()" [disabled]="ro" />
        <label class="form-check-label" [for]="id + 'eligible'">Somente casos elegíveis</label>
      </div>
      <span class="app-muted small">{{ c.eligibleOnly === false ? 'Inclui casos bloqueados (ex.: contestação, carteira suspensa): use só para comunicados.' : 'Regra RB02: casos bloqueados ficam fora.' }}</span>
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
                  <!-- o grupo é nomeado pelo rótulo do filtro (ex.: "Situação do caso") -->
                  <fieldset class="ce-wide" [attr.aria-labelledby]="id + f.key">
                    @for (s of caseStatuses; track s.value) {
                      <div class="form-check form-check-inline">
                        <input class="form-check-input" type="checkbox" [id]="id + 'status-' + s.value" [checked]="has(c.caseStatuses, s.value)" (change)="toggle('caseStatuses', s.value)" [disabled]="ro" />
                        <label class="form-check-label" [for]="id + 'status-' + s.value">{{ s.label }}</label>
                      </div>
                    }
                  </fieldset>
                }
                @case ('owners') {
                  <fieldset class="ce-wide" [attr.aria-labelledby]="id + f.key">
                    @for (o of owners; track o) {
                      <div class="form-check form-check-inline">
                        <input class="form-check-input" type="checkbox" [id]="id + 'owner-' + o" [checked]="has(c.ownerTypes, o)" (change)="toggle('ownerTypes', o)" [disabled]="ro" />
                        <label class="form-check-label" [for]="id + 'owner-' + o">{{ ownerLabels[o] }}</label>
                      </div>
                    }
                  </fieldset>
                }
                @case ('channel') {
                  <app-field label="Canal">
                    <select appInput [(ngModel)]="c.contactableChannel" (ngModelChange)="changed()" [disabled]="ro">
                      <option [ngValue]="null" disabled>Selecione</option>
                      @for (ch of channels; track ch) {
                        <option [ngValue]="ch">{{ channelLabels[ch] }}</option>
                      }
                    </select>
                  </app-field>
                }
                @case ('yes-no') {
                  <app-field class="ce-wide" [label]="f.label">
                    <select appInput [ngModel]="boolValue(f)" (ngModelChange)="setBool(f, $event)" [disabled]="ro">
                      <option [ngValue]="true">{{ f.yes }}</option>
                      <option [ngValue]="false">{{ f.no }}</option>
                    </select>
                  </app-field>
                }
                @case ('int') {
                  <app-field label="Dias">
                    <input appInput type="number" min="0" [max]="f.max ?? 36500" step="1" [ngModel]="num(f.fields[0])" (ngModelChange)="setNum(f.fields[0], $event)" [disabled]="ro" />
                  </app-field>
                }
                @default {
                  <app-field label="Mínimo">
                    <input appInput type="number" min="0" [max]="f.max ?? null" [step]="f.kind === 'money-range' ? 0.01 : 1" [ngModel]="num(f.fields[0])" (ngModelChange)="setNum(f.fields[0], $event)" [disabled]="ro" />
                  </app-field>
                  <app-field label="Máximo">
                    <input appInput type="number" min="0" [max]="f.max ?? null" [step]="f.kind === 'money-range' ? 0.01 : 1" [ngModel]="num(f.fields[1])" (ngModelChange)="setNum(f.fields[1], $event)" [disabled]="ro" />
                  </app-field>
                }
              }
            </div>
            @if (f.hint) {
              <span class="app-muted small mt-1">{{ f.hint }}</span>
            }
          </div>
          @if (!ro) {
            <button class="btn btn-link" type="button" (click)="remove(f)" [attr.aria-label]="'Remover filtro ' + f.label" ngbTooltip="Remover filtro">
              <app-icon name="x-lg" />
            </button>
          }
        </li>
      } @empty {
        <li class="app-muted small py-1">Nenhum filtro além da elegibilidade: todos os casos {{ c.eligibleOnly === false ? '' : 'elegíveis ' }}entram.</li>
      }
    </ul>

    @if (!ro && hiddenFilters().length) {
      <div ngbDropdown class="d-inline-flex">
        <button class="btn btn-outline-primary d-inline-flex align-items-center gap-2" type="button" ngbDropdownToggle data-tour="segment-add-filter">
          <app-icon name="plus-lg" />
          Adicionar filtro
          <app-icon name="chevron-down" />
        </button>
        <div ngbDropdownMenu class="shadow app-menu ce-menu" aria-label="Filtros disponíveis">
          @for (g of groups(); track g.group; let firstGroup = $first) {
            @if (!firstGroup) {
              <div class="dropdown-divider" role="separator"></div>
            }
            <h3 class="dropdown-header">{{ g.group }}</h3>
            @for (f of g.filters; track f.key) {
              <button ngbDropdownItem type="button" (click)="add(f)">{{ f.label }}</button>
            }
          }
        </div>
      </div>
    }
  `,
  styles: `
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
      border: var(--bs-border-width) solid var(--bs-border-color);
      border-radius: var(--bs-border-radius);
      background: var(--bs-body-bg);
    }
    .ce-and {
      flex: 0 0 44px;
      padding-top: 4px;
      font-size: var(--app-text-xs);
      font-weight: 600;
      letter-spacing: 0.06em;
      text-transform: uppercase;
      color: var(--bs-primary-text-emphasis);
    }
    .ce-body {
      display: flex;
      flex: 1 1 auto;
      flex-direction: column;
      min-width: 0;
      padding-bottom: 8px;
    }
    .ce-label {
      margin-bottom: 4px;
      font-size: var(--app-text-sm);
      font-weight: 500;
    }
    .ce-fields {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
      app-field {
        flex: 0 1 160px;
        min-width: 120px;
      }
      .ce-wide {
        flex: 1 1 260px;
      }
    }
    .ce-menu {
      max-height: 60vh;
      overflow-y: auto;
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

  protected has(list: readonly string[] | null | undefined, value: string): boolean {
    return !!list?.includes(value);
  }

  /** Marca/desmarca uma opção da lista múltipla (equivale à seleção múltipla anterior). */
  protected toggle(field: 'caseStatuses' | 'ownerTypes', value: string): void {
    const c = this.criteria() as unknown as Record<string, string[] | null>;
    const list = c[field] ?? [];
    c[field] = list.includes(value) ? list.filter((v) => v !== value) : [...list, value];
    this.changed();
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
