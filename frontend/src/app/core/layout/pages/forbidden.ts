import { Component } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { RouterLink } from '@angular/router';

@Component({
  selector: 'app-forbidden',
  imports: [MatButtonModule, RouterLink],
  template: `
    <section class="app-message-page" aria-labelledby="forbidden-title">
      <h1 id="forbidden-title">Acesso negado</h1>
      <p>Seu perfil não tem permissão para acessar esta página. Se precisar do acesso, solicite ao administrador da assessoria.</p>
      <a mat-flat-button routerLink="/">Ir para o início</a>
    </section>
  `,
})
export class Forbidden {}
