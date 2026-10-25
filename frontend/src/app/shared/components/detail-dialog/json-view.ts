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
      border-radius: var(--bs-border-radius-sm);
      background: var(--bs-tertiary-bg);
      color: var(--bs-body-color);
      font: 12px/1.5 var(--bs-font-monospace);
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
