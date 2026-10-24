import { Component, DOCUMENT, OnInit, computed, inject, input, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { RouterLink } from '@angular/router';
import { PortalApi, portalMessage } from '../data/portal.api';
import { KNOWLEDGE_LABELS } from '../data/portal.labels';
import { ChallengeView } from '../data/portal.models';
import { PortalFlow } from '../data/portal-flow';
import { lockMessage } from './portal-entry';

/**
 * Entrada pelo link de uso único (`/portal/:slug/entrar#<token>`). O token vem no fragmento (não vai
 * para servidores intermediários nem para o cabeçalho Referer) e é apagado do endereço antes do envio.
 */
@Component({
  selector: 'app-portal-link',
  imports: [ReactiveFormsModule, RouterLink, MatButtonModule, MatFormFieldModule, MatIconModule, MatInputModule],
  template: `
    <section class="portal-card">
      @if (step(); as s) {
        <h1 class="portal-title">Confirme seus dados</h1>
        <p class="portal-subtitle">Para sua segurança, informe o dado abaixo.</p>
        <form (ngSubmit)="confirm(s)" novalidate>
          <mat-form-field class="portal-field" appearance="outline">
            <mat-label>{{ knowledge()?.label }}</mat-label>
            <input matInput [formControl]="answer" autocomplete="off" maxlength="20" aria-describedby="k-hint" />
            <mat-hint id="k-hint">{{ knowledge()?.hint }}</mat-hint>
          </mat-form-field>
          @if (error()) {
            <p class="portal-alert" role="alert"><mat-icon aria-hidden="true">error</mat-icon><span>{{ error() }}</span></p>
          }
          <div class="portal-actions">
            <button mat-flat-button type="submit" [disabled]="busy()">Entrar</button>
          </div>
        </form>
      } @else if (error()) {
        <h1 class="portal-title">Não foi possível entrar pelo link</h1>
        <p class="portal-alert" role="alert"><mat-icon aria-hidden="true">error</mat-icon>
          <span>{{ error() }} O link vale uma única vez e por tempo limitado.</span></p>
        <div class="portal-actions">
          <a mat-flat-button [routerLink]="['/portal', slug()]">Entrar com CPF ou CNPJ</a>
        </div>
      } @else {
        <p class="portal-loading" role="status" aria-live="polite">Verificando o link…</p>
      }
    </section>
  `,
})
export class PortalLink implements OnInit {
  readonly slug = input.required<string>();

  private readonly api = inject(PortalApi);
  private readonly flow = inject(PortalFlow);
  private readonly document = inject(DOCUMENT);

  protected readonly answer = new FormControl('', { nonNullable: true });
  protected readonly step = signal<ChallengeView | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly knowledge = computed(() => {
    const type = this.step()?.knowledgeChallenge;
    return type && type !== 'NONE' ? KNOWLEDGE_LABELS[type] : null;
  });

  ngOnInit(): void {
    const view = this.document.defaultView;
    const token = (view?.location.hash ?? '').replace(/^#/, '');
    // o token sai do endereço (histórico, favoritos, compartilhamento de tela) antes de qualquer envio
    view?.history.replaceState(view.history.state, '', view.location.pathname);
    if (!/^[A-Za-z]{45}$/.test(token)) {
      this.error.set('O link está incompleto ou foi alterado.');
      return;
    }
    this.api.redeemLink(this.slug(), token).subscribe({
      next: (result) => {
        if (result.session) {
          this.flow.enter(this.slug(), result.session);
        } else {
          this.step.set(result.knowledgeStep);
        }
      },
      error: (e: unknown) => this.error.set(lockMessage(e) ?? portalMessage(e)),
    });
  }

  protected confirm(step: ChallengeView): void {
    if (this.busy()) {
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    this.api.verify(this.slug(), step.challengeId, null, this.answer.value).subscribe({
      next: (grant) => this.flow.enter(this.slug(), grant),
      error: (e: unknown) => {
        this.busy.set(false);
        this.error.set(lockMessage(e) ?? portalMessage(e));
      },
    });
  }
}
