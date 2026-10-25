import { AfterContentInit, DestroyRef, Directive, ElementRef, computed, inject, input, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ControlContainer, FormGroupDirective, NgControl, NgForm } from '@angular/forms';
import { merge } from 'rxjs';
import { DensityService } from '../../core/preferences/density.service';
import { errorMessage } from '../validation/error-messages';

let nextId = 0;

/**
 * Campo de formulário Bootstrap ligado a um controle reativo (`formControlName`/`formControl`):
 * aplica `form-control` (ou `form-select` em `<select>`), o tamanho da densidade compacta,
 * `is-invalid` + `aria-invalid` quando o controle é inválido e foi tocado (ou o formulário
 * submetido), e `aria-describedby` com a ajuda e o erro renderizados pelo `app-field`.
 *
 * ```html
 * <input appInput formControlName="email" type="email" />
 * <select appInput formControlName="status">…</select>
 * ```
 */
@Directive({
  selector: 'input[appInput], select[appInput], textarea[appInput]',
  host: {
    '[id]': 'id()',
    '[class.form-control]': '!select',
    '[class.form-select]': 'select',
    '[class.form-control-sm]': '!select && density.compact()',
    '[class.form-select-sm]': 'select && density.compact()',
    '[class.is-invalid]': 'invalid()',
    '[attr.aria-invalid]': "invalid() ? 'true' : null",
    '[attr.aria-describedby]': 'describedBy()',
  },
})
export class FieldControl implements AfterContentInit {
  private readonly ngControl = inject(NgControl, { optional: true, self: true });
  private readonly container = inject(ControlContainer, { optional: true });
  private readonly destroyRef = inject(DestroyRef);
  protected readonly density = inject(DensityService);
  protected readonly select = (inject(ElementRef).nativeElement as HTMLElement).tagName === 'SELECT';

  readonly id = input(`app-field-${++nextId}`);

  /** Muda a cada evento do controle (valor, status, toque) ou submissão do formulário. */
  private readonly version = signal(0);
  private readonly ids = signal<string | null>(null);
  protected readonly describedBy = this.ids.asReadonly();

  readonly invalid = computed(() => {
    this.version();
    const control = this.ngControl?.control;
    return !!control && control.invalid && (control.touched || this.submitted());
  });

  readonly message = computed(() => {
    this.version();
    return errorMessage(this.ngControl?.control?.errors);
  });

  ngAfterContentInit(): void {
    const control = this.ngControl?.control;
    if (!control) {
      return;
    }
    const form = this.container?.formDirective as FormGroupDirective | NgForm | null | undefined;
    merge(control.events, ...(form ? [form.ngSubmit] : []))
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.version.update((v) => v + 1));
    this.version.update((v) => v + 1);
  }

  /** @internal — o `app-field` informa os ids da ajuda/erro. */
  setDescribedBy(ids: string | null): void {
    this.ids.set(ids);
  }

  private submitted(): boolean {
    const form = this.container?.formDirective as FormGroupDirective | NgForm | null | undefined;
    return !!form?.submitted;
  }
}
