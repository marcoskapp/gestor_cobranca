import { Pipe, PipeTransform, inject } from '@angular/core';
import { ManualParameterValues, resolveManualText } from './manual-parameters';
import { ManualParameters } from './manual-parameters.service';

/**
 * Texto do manual, do tour, das novidades ou do "i" com os marcadores de parâmetro resolvidos
 * (`{{ texto | manualText }}`, ux.md §8.7). Impuro porque o resultado depende do sinal com os valores (lido aqui,
 * o template atualiza quando eles chegam); guarda o último resultado para não refazer a troca a cada verificação.
 */
@Pipe({ name: 'manualText', pure: false })
export class ManualTextPipe implements PipeTransform {
  private readonly parameters = inject(ManualParameters);
  private lastText: string | null = null;
  private lastValues: ManualParameterValues | null | undefined = undefined;
  private lastResult = '';

  constructor() {
    this.parameters.load();
  }

  transform(text: string | null | undefined): string {
    if (text === null || text === undefined) {
      return '';
    }
    const values = this.parameters.values();
    if (text !== this.lastText || values !== this.lastValues) {
      this.lastText = text;
      this.lastValues = values;
      this.lastResult = resolveManualText(text, values);
    }
    return this.lastResult;
  }
}
