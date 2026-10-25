import { Component, DestroyRef, OnInit, inject, input, output } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl } from '@angular/forms';
import { EntityPicker, EntitySource } from '../../../shared/components/entity-picker/entity-picker';

/**
 * Ponte entre uma lista de ids das regras do plano (editadas no lugar) e o `app-entity-picker`
 * (que trabalha com `FormControl<string[]>`).
 */
@Component({
  selector: 'app-ids-field',
  imports: [EntityPicker],
  template: `<app-entity-picker [control]="control" [source]="source()" [label]="label()" [hint]="hint()" />`,
})
export class IdsField implements OnInit {
  /** Lista alterada no lugar (mesma referência do modelo). */
  readonly ids = input.required<string[]>();
  readonly source = input.required<EntitySource>();
  readonly label = input.required<string>();
  readonly hint = input<string>();
  readonly disabled = input(false);
  readonly changed = output<void>();

  protected readonly control = new FormControl<string[]>([], { nonNullable: true });
  private readonly destroyRef = inject(DestroyRef);

  ngOnInit(): void {
    this.control.setValue([...this.ids()], { emitEvent: false });
    if (this.disabled()) {
      this.control.disable({ emitEvent: false });
    }
    this.control.valueChanges.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((value) => {
      const list = this.ids();
      list.splice(0, list.length, ...value);
      this.changed.emit();
    });
  }
}
