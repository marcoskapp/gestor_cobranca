import { Component, computed, input } from '@angular/core';

/** Exibe um objeto JSON como texto pré-formatado (interpolação segura, nunca HTML). */
@Component({
  selector: 'app-json-view',
  template: `<pre class="app-json">{{ text() }}</pre>`,
  styles: `
    .app-json {
      margin: 0;
      padding: 8px 12px;
      max-height: 280px;
      overflow: auto;
      border-radius: 4px;
      background: var(--mat-sys-surface-container);
      font: 12px/1.5 ui-monospace, 'SFMono-Regular', Menlo, Consolas, monospace;
      white-space: pre-wrap;
      word-break: break-word;
    }
  `,
})
export class JsonView {
  readonly value = input<unknown>();
  protected readonly text = computed(() => {
    const value = this.value();
    if (value === null || value === undefined) {
      return '—';
    }
    return JSON.stringify(value, null, 2);
  });
}
