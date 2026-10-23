import { Directive, ElementRef, inject, input } from '@angular/core';
import { NgControl } from '@angular/forms';
import { DocumentType, formatDocument } from '../validation/document';

/**
 * Máscara de CPF/CNPJ enquanto o usuário digita (`<input appDocumentMask="CNPJ">`). O valor do
 * controle fica formatado; o backend normaliza para dígitos (`Document.parse`).
 */
@Directive({
  selector: 'input[appDocumentMask]',
  host: {
    inputmode: 'numeric',
    autocomplete: 'off',
    '(input)': 'onInput()',
  },
})
export class DocumentMask {
  readonly type = input<DocumentType | '' | undefined>(undefined, { alias: 'appDocumentMask' });
  private readonly element = inject<ElementRef<HTMLInputElement>>(ElementRef);
  private readonly control = inject(NgControl, { optional: true, self: true });

  protected onInput(): void {
    const el = this.element.nativeElement;
    const formatted = formatDocument(el.value, this.type() || undefined);
    if (formatted !== el.value) {
      el.value = formatted;
      this.control?.control?.setValue(formatted, { emitEvent: true });
    }
  }
}
