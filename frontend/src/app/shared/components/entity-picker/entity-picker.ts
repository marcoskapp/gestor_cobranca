import { Component, DestroyRef, OnInit, computed, inject, input, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { MatAutocompleteModule, MatAutocompleteSelectedEvent } from '@angular/material/autocomplete';
import { MatChipsModule } from '@angular/material/chips';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { Observable, catchError, debounceTime, distinctUntilChanged, of, startWith, switchMap } from 'rxjs';
import { Option } from '../../models/lookup';

/** Fonte de dados do seletor: busca no servidor e resolução de rótulos dos ids já escolhidos. */
export interface EntitySource {
  search(query: string): Observable<Option[]>;
  resolve(ids: string[]): Observable<Option[]>;
}

/**
 * Seleção de entidades com busca no servidor (autocomplete) e chips. O valor do controle é a
 * lista de ids; em modo `single`, no máximo um id.
 */
@Component({
  selector: 'app-entity-picker',
  imports: [ReactiveFormsModule, MatFormFieldModule, MatInputModule, MatChipsModule, MatAutocompleteModule, MatIconModule],
  template: `
    <mat-form-field class="app-full-width">
      <mat-label>{{ label() }}</mat-label>
      <mat-chip-grid #grid [attr.aria-label]="label()" [disabled]="control().disabled">
        @for (item of selected(); track item.value) {
          <mat-chip-row (removed)="remove(item.value)">
            {{ item.label }}
            <button matChipRemove type="button" [attr.aria-label]="'Remover ' + item.label">
              <mat-icon aria-hidden="true">cancel</mat-icon>
            </button>
          </mat-chip-row>
        }
        <input
          [formControl]="query"
          [matChipInputFor]="grid"
          [matAutocomplete]="auto"
          [placeholder]="placeholder()"
          autocomplete="off"
        />
      </mat-chip-grid>
      <mat-autocomplete #auto="matAutocomplete" (optionSelected)="add($event)">
        @for (option of available(); track option.value) {
          <mat-option [value]="option">{{ option.label }}</mat-option>
        } @empty {
          <mat-option disabled>Nenhum resultado</mat-option>
        }
      </mat-autocomplete>
      @if (hint()) {
        <mat-hint>{{ hint() }}</mat-hint>
      }
    </mat-form-field>
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
  protected readonly query = new FormControl<string | Option>('', { nonNullable: true });
  private readonly labels = signal<ReadonlyMap<string, string>>(new Map());
  private readonly ids = signal<string[]>([]);
  private readonly results = signal<Option[]>([]);

  protected readonly selected = computed(() =>
    this.ids().map((id) => ({ value: id, label: this.labels().get(id) ?? 'Carregando…' })),
  );
  protected readonly available = computed(() => {
    const chosen = new Set(this.ids());
    return this.results().filter((o) => !chosen.has(o.value));
  });

  ngOnInit(): void {
    const control = this.control();
    this.ids.set([...(control.value ?? [])]);
    control.valueChanges.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((value) => {
      this.ids.set([...(value ?? [])]);
      this.resolveMissing();
    });
    this.resolveMissing();

    this.query.valueChanges
      .pipe(
        startWith(''),
        debounceTime(300),
        distinctUntilChanged(),
        switchMap((value) =>
          typeof value === 'string' ? this.source().search(value.trim()).pipe(catchError(() => of<Option[]>([]))) : of(null),
        ),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((options) => {
        if (options) {
          this.results.set(options);
          this.remember(options);
        }
      });
  }

  protected add(event: MatAutocompleteSelectedEvent): void {
    const option = event.option.value as Option;
    this.remember([option]);
    const next = this.single() ? [option.value] : [...this.ids().filter((id) => id !== option.value), option.value];
    this.commit(next);
    this.query.setValue('');
  }

  protected remove(id: string): void {
    this.commit(this.ids().filter((value) => value !== id));
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
