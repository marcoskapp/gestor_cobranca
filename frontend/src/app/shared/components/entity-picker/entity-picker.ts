import { Component, DestroyRef, OnInit, computed, inject, input, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { NgbTypeahead, NgbTypeaheadSelectItemEvent } from '@ng-bootstrap/ng-bootstrap';
import { Observable, Subject, catchError, debounceTime, distinctUntilChanged, map, merge, of, switchMap, tap } from 'rxjs';
import { DensityService } from '../../../core/preferences/density.service';
import { Option } from '../../models/lookup';

/** Fonte de dados do seletor: busca no servidor e resolução de rótulos dos ids já escolhidos. */
export interface EntitySource {
  search(query: string): Observable<Option[]>;
  resolve(ids: string[]): Observable<Option[]>;
}

let nextId = 0;

/**
 * Seleção de entidades com busca no servidor (typeahead do ng-bootstrap, padrão combobox da
 * WAI-ARIA) e selos removíveis. O valor do controle é a lista de ids; em modo `single`, no
 * máximo um id. Mesma API do componente anterior (Angular Material).
 */
@Component({
  selector: 'app-entity-picker',
  imports: [ReactiveFormsModule, NgbTypeahead],
  host: { class: 'd-block' },
  template: `
    <label class="form-label" [for]="inputId">{{ label() }}</label>
    <div
      class="form-control app-picker"
      [class.form-control-sm]="density.compact()"
      [class.is-invalid]="invalid()"
      [class.app-picker--disabled]="disabled()"
      [class.app-picker--single]="single()"
    >
      @for (item of selected(); track item.value) {
        <span
          class="badge rounded-pill bg-secondary-subtle text-secondary-emphasis border border-secondary-subtle app-picker__chip"
          [title]="item.label"
        >
          <span class="text-truncate">{{ item.label }}</span>
          <button
            type="button"
            class="btn-close app-picker__remove"
            [disabled]="disabled()"
            [attr.aria-label]="'Remover ' + item.label"
            (click)="remove(item.value)"
          ></button>
        </span>
      }
      <input
        class="app-picker__input"
        type="text"
        [id]="inputId"
        [formControl]="query"
        [ngbTypeahead]="search"
        [resultFormatter]="formatOption"
        [inputFormatter]="formatOption"
        [editable]="false"
        [placeholder]="placeholder()"
        [attr.aria-describedby]="hint() || empty() ? hintId : null"
        [attr.aria-invalid]="invalid() ? 'true' : null"
        autocomplete="off"
        (focus)="focus$.next(query.value)"
        (selectItem)="add($event)"
      />
    </div>
    <div class="form-text" [id]="hintId" aria-live="polite">
      @if (empty()) {
        Nenhum resultado para a busca.
      } @else {
        {{ hint() }}
      }
    </div>
  `,
  styles: `
    .app-picker {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 4px;
      min-height: calc(1.5em + 0.875rem + 2px);
      padding-block: 0.25rem;
      cursor: text;
    }
    .app-picker:focus-within {
      border-color: var(--bs-primary);
      box-shadow: 0 0 0 0.1875rem rgba(var(--bs-primary-rgb), 0.45);
    }
    .app-picker--disabled {
      background-color: var(--bs-secondary-bg);
    }
    .app-picker__chip {
      display: inline-flex;
      align-items: center;
      gap: 4px;
      /* selo nunca maior que o campo: o rótulo longo é cortado com reticências (texto completo no
         title e no rótulo acessível do botão de remover) */
      max-width: 100%;
      min-width: 0;
      font-weight: var(--bs-body-font-weight);
    }
    .app-picker__remove {
      flex: none;
      --bs-btn-close-width: 0.5em;
      --bs-btn-close-height: 0.5em;
    }
    .app-picker__input {
      flex: 1 1 120px;
      min-width: 120px;
      border: 0;
      outline: 0;
      background: transparent;
      color: inherit;
    }
    /* seleção única: selo e busca na mesma linha, com a altura de um campo comum — o campo não cresce
       ao escolher e fica alinhado aos demais campos e aos botões da linha de filtros. O selo encolhe
       (reticências) e a busca mantém espaço para digitar a troca. */
    .app-picker--single {
      flex-wrap: nowrap;
    }
    .app-picker--single .app-picker__chip {
      flex: 0 1 auto;
    }
    .app-picker--single .app-picker__input {
      flex: 1 1 4rem;
      min-width: 4rem;
    }
    /* densidade compacta: mesma altura de .form-control-sm, também com um item escolhido */
    .app-picker.form-control-sm {
      min-height: calc(1.5em + 0.5rem + 2px);
      padding-block: 0.125rem;
    }
    .app-picker.form-control-sm .app-picker__chip {
      padding-block: 1px;
    }
  `,
})
export class EntityPicker implements OnInit {
  readonly control = input.required<FormControl<string[]>>();
  readonly source = input.required<EntitySource>();
  readonly label = input.required<string>();
  readonly placeholder = input('Pesquisar…');
  readonly hint = input<string>();
  readonly single = input(false);

  private readonly destroyRef = inject(DestroyRef);
  protected readonly density = inject(DensityService);
  protected readonly inputId = `app-entity-picker-${++nextId}`;
  protected readonly hintId = `${this.inputId}-hint`;
  protected readonly query = new FormControl<string | Option>('', { nonNullable: true });
  protected readonly focus$ = new Subject<string | Option>();
  private readonly labels = signal<ReadonlyMap<string, string>>(new Map());
  private readonly ids = signal<string[]>([]);
  private readonly state = signal(0);
  protected readonly empty = signal(false);

  protected readonly selected = computed(() =>
    this.ids().map((id) => ({ value: id, label: this.labels().get(id) ?? 'Carregando…' })),
  );
  protected readonly disabled = computed(() => {
    this.state();
    return this.control().disabled;
  });
  protected readonly invalid = computed(() => {
    this.state();
    const control = this.control();
    return control.invalid && control.touched;
  });

  protected readonly formatOption = (option: Option | string): string => (typeof option === 'string' ? option : option.label);

  /** Busca no servidor ao digitar ou focar; opções já escolhidas ficam de fora. */
  protected readonly search = (text$: Observable<string>): Observable<readonly Option[]> =>
    merge(text$.pipe(debounceTime(300), distinctUntilChanged()), this.focus$.pipe(map((v) => (typeof v === 'string' ? v : '')))).pipe(
      switchMap((term) => this.source().search(term.trim()).pipe(catchError(() => of<Option[]>([])))),
      tap((options) => this.remember(options)),
      map((options) => {
        const chosen = new Set(this.ids());
        return options.filter((o) => !chosen.has(o.value));
      }),
      tap((options) => this.empty.set(options.length === 0)),
    );

  ngOnInit(): void {
    const control = this.control();
    this.ids.set([...(control.value ?? [])]);
    control.valueChanges.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((value) => {
      this.ids.set([...(value ?? [])]);
      this.resolveMissing();
    });
    control.events.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => this.state.update((v) => v + 1));
    this.syncDisabled(control.disabled);
    control.statusChanges.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => this.syncDisabled(control.disabled));
    this.resolveMissing();
  }

  protected add(event: NgbTypeaheadSelectItemEvent<Option>): void {
    // mantém o campo de busca vazio: a escolha vira um selo
    event.preventDefault();
    const option = event.item;
    this.remember([option]);
    const next = this.single() ? [option.value] : [...this.ids().filter((id) => id !== option.value), option.value];
    this.commit(next);
    this.query.setValue('');
    this.empty.set(false);
  }

  protected remove(id: string): void {
    this.commit(this.ids().filter((value) => value !== id));
  }

  private syncDisabled(disabled: boolean): void {
    if (disabled && this.query.enabled) {
      this.query.disable({ emitEvent: false });
    } else if (!disabled && this.query.disabled) {
      this.query.enable({ emitEvent: false });
    }
  }

  private commit(ids: string[]): void {
    const control = this.control();
    control.setValue(ids);
    control.markAsDirty();
    control.markAsTouched();
  }

  private remember(options: Option[]): void {
    const map = new Map(this.labels());
    for (const option of options) {
      map.set(option.value, option.label);
    }
    this.labels.set(map);
  }

  private resolveMissing(): void {
    const missing = this.ids().filter((id) => !this.labels().has(id));
    if (missing.length === 0) {
      return;
    }
    this.source()
      .resolve(missing)
      .pipe(
        catchError(() => of(missing.map((id) => ({ value: id, label: 'Registro indisponível' })))),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((options) => this.remember(options));
  }
}
