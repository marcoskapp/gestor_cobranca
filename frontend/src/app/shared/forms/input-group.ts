import { Directive, inject } from '@angular/core';
import { DensityService } from '../../core/preferences/density.service';

/**
 * `input-group` do Bootstrap na densidade compacta: aplica `.input-group-sm`, para o texto ao lado
 * ("R$", ícone de busca) acompanhar o `.form-control-sm` que o `appInput` já aplica ao campo — sem
 * isso o grupo estica o campo até a altura do texto. Vem com `FORM_FIELD`.
 */
@Directive({
  // seletor de classe de propósito: vale para toda a marcação `input-group` do Bootstrap, sem atributo extra
  // eslint-disable-next-line @angular-eslint/directive-selector
  selector: 'div.input-group',
  host: { '[class.input-group-sm]': 'density.compact()' },
})
export class InputGroupDensity {
  protected readonly density = inject(DensityService);
}
