import { Component, computed, input, output } from '@angular/core';
import { RouterLink } from '@angular/router';
import { PROFILE_LABELS } from './manual.models';
import { ScreenManualView } from './manual.service';

/**
 * Conteúdo do manual rápido de uma tela: para que serve, quem usa, encaixe no todo, telas
 * relacionadas e termos. Usado no painel "Sobre esta tela" e em cada tela do manual do módulo.
 * Texto só por interpolação (o conteúdo é dado, nunca HTML).
 */
@Component({
  selector: 'app-screen-manual-body',
  imports: [RouterLink],
  template: `
    @let v = view();
    <p class="manual__objective">{{ v.manual.objective }}</p>

    <section class="manual__section" [attr.aria-labelledby]="ids().purpose">
      <div role="heading" [attr.aria-level]="level()" class="manual__heading" [id]="ids().purpose">Para que serve</div>
      @for (paragraph of v.manual.purpose; track $index) {
        <p class="manual__text">{{ paragraph }}</p>
      }
    </section>

    <section class="manual__section" [attr.aria-labelledby]="ids().profiles">
      <div role="heading" [attr.aria-level]="level()" class="manual__heading" [id]="ids().profiles">Quem usa</div>
      <ul class="manual__profiles">
        @for (profile of profiles(); track profile) {
          <li class="badge rounded-pill border manual__profile">{{ profile }}</li>
        }
      </ul>
    </section>

    <section class="manual__section" [attr.aria-labelledby]="ids().flow">
      <div role="heading" [attr.aria-level]="level()" class="manual__heading" [id]="ids().flow">Como se encaixa no sistema</div>
      <div class="manual__flow">
        <div>
          <p class="manual__label">De onde vêm os dados</p>
          <ul class="manual__list">
            @for (item of v.manual.inputs; track $index) {
              <li>{{ item }}</li>
            }
          </ul>
        </div>
        <div>
          <p class="manual__label">Para onde vai o resultado</p>
          <ul class="manual__list">
            @for (item of v.manual.outputs; track $index) {
              <li>{{ item }}</li>
            }
          </ul>
        </div>
      </div>
    </section>

    @if (v.related.length > 0) {
      <section class="manual__section" [attr.aria-labelledby]="ids().related">
        <div role="heading" [attr.aria-level]="level()" class="manual__heading" [id]="ids().related">Telas relacionadas</div>
        <ul class="manual__list manual__related">
          @for (item of v.related; track item.screen) {
            <li>
              @if (item.route) {
                <a [routerLink]="item.route" (click)="navigate.emit()">{{ item.title }}</a>
              } @else {
                <span class="manual__related-title">{{ item.title }}</span>
              }
              <span class="app-muted"> — {{ item.why }}</span>
            </li>
          }
        </ul>
      </section>
    }

    @if (v.terms.length > 0) {
      <section class="manual__section" [attr.aria-labelledby]="ids().terms">
        <div role="heading" [attr.aria-level]="level()" class="manual__heading" [id]="ids().terms">Termos</div>
        <dl class="manual__terms">
          @for (term of v.terms; track term.id) {
            <dt>{{ term.term }}</dt>
            <dd>{{ term.definition }}</dd>
          }
        </dl>
      </section>
    }
  `,
  styles: `
    :host {
      display: block;
    }
    .manual__objective {
      margin: 0 0 var(--app-space-4);
      font-size: var(--app-text-md);
      font-weight: 400;
      line-height: var(--app-line-normal);
    }
    .manual__section + .manual__section {
      margin-top: var(--app-space-4);
    }
    .manual__heading {
      margin: 0 0 var(--app-space-2);
      font-size: var(--app-text-xs);
      font-weight: 600;
      letter-spacing: 0.04em;
      text-transform: uppercase;
      color: var(--bs-secondary-color);
    }
    .manual__text {
      margin: 0 0 var(--app-space-2);
      line-height: var(--app-line-normal);
    }
    .manual__profiles {
      display: flex;
      flex-wrap: wrap;
      gap: var(--app-space-2);
      margin: 0;
      padding: 0;
      list-style: none;
    }
    .manual__profile {
      font-weight: 400;
      font-size: var(--app-text-xs);
      color: var(--bs-body-color);
      background: var(--bs-tertiary-bg);
    }
    .manual__flow {
      display: grid;
      gap: var(--app-space-3);
    }
    .manual__label {
      margin: 0 0 var(--app-space-1);
      font-weight: 500;
    }
    .manual__list {
      margin: 0;
      padding-left: 20px;
      line-height: var(--app-line-normal);
    }
    .manual__related li + li {
      margin-top: var(--app-space-1);
    }
    .manual__related-title {
      font-weight: 500;
    }
    .manual__terms {
      margin: 0;
    }
    .manual__terms dt {
      font-weight: 500;
    }
    .manual__terms dd {
      margin: 0 0 var(--app-space-2);
      color: var(--bs-secondary-color);
      line-height: var(--app-line-normal);
    }
  `,
})
export class ScreenManualBody {
  readonly view = input.required<ScreenManualView>();
  /** Nível dos títulos das seções (3 no painel, 4 dentro do manual do módulo). */
  readonly level = input(3);
  /** Emitido ao seguir um link (o painel fecha). */
  readonly navigate = output<void>();

  protected readonly profiles = computed(() => this.view().manual.profiles.map((p) => PROFILE_LABELS[p]));
  protected readonly ids = computed(() => {
    const base = 'manual-' + this.view().manual.id.replace(/\./g, '-');
    return {
      purpose: `${base}-purpose`,
      profiles: `${base}-profiles`,
      flow: `${base}-flow`,
      related: `${base}-related`,
      terms: `${base}-terms`,
    };
  });
}
