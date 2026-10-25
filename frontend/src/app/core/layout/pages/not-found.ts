import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';

@Component({
  selector: 'app-not-found',
  imports: [RouterLink],
  template: `
    <section class="app-message-page" aria-labelledby="not-found-title">
      <h1 id="not-found-title">Página não encontrada</h1>
      <p>O endereço acessado não existe ou foi movido.</p>
      <a class="btn btn-primary" routerLink="/">Ir para o início</a>
    </section>
  `,
})
export class NotFound {}
