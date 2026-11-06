import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { FormControl } from '@angular/forms';
import { Observable, of } from 'rxjs';
import { Option } from '../../models/lookup';
import { EntityPicker, EntitySource } from './entity-picker';

/** Fonte fictícia: só "Ana" encontra alguém; dígitos explicam por que não há resultados. */
class FakeSource implements EntitySource {
  readonly placeholder = 'Informe o CPF/CNPJ completo ou o nome';
  search(q: string): Observable<Option[]> {
    return of(q.startsWith('Ana') ? [{ value: 'd-1', label: 'Ana Paula Ribeiro' }] : []);
  }
  resolve(ids: string[]): Observable<Option[]> {
    return of(ids.map((value) => ({ value, label: 'Ana Paula Ribeiro' })));
  }
  emptyReason(q: string): string | null {
    return /^\d+$/.test(q) ? 'CPF/CNPJ incompleto.' : null;
  }
}

@Component({
  imports: [EntityPicker],
  template: `
    <app-entity-picker [control]="withSource" [source]="source" label="Devedor" [single]="true" />
    <app-entity-picker [control]="explicit" [source]="source" label="Outro" placeholder="Buscar pessoa" hint="Texto auxiliar." />
  `,
})
class Host {
  readonly source = new FakeSource();
  readonly withSource = new FormControl<string[]>([], { nonNullable: true });
  readonly explicit = new FormControl<string[]>([], { nonNullable: true });
}

describe('EntityPicker', () => {
  let fixture: ComponentFixture<Host>;
  const inputs = () => [...(fixture.nativeElement as HTMLElement).querySelectorAll<HTMLInputElement>('input.app-picker__input')];
  const hints = () => [...(fixture.nativeElement as HTMLElement).querySelectorAll<HTMLElement>('.form-text')];

  async function searchFor(input: HTMLInputElement, value: string): Promise<void> {
    // digitação: a busca sai depois da espera do typeahead (300 ms)
    input.value = value;
    input.dispatchEvent(new Event('input'));
    await new Promise((resolve) => setTimeout(resolve, 350));
    fixture.detectChanges();
  }

  beforeEach(() => {
    fixture = TestBed.createComponent(Host);
    fixture.detectChanges();
  });

  afterEach(() => fixture.destroy());

  it('usa a dica da fonte quando o seletor não define placeholder; a do seletor prevalece', () => {
    expect(inputs()[0].placeholder).toBe('Informe o CPF/CNPJ completo ou o nome');
    expect(inputs()[1].placeholder).toBe('Buscar pessoa');
  });

  it('em seleção única com um item escolhido, a dica sai para não espremer o selo', () => {
    fixture.componentInstance.withSource.setValue(['d-1']);
    fixture.detectChanges();
    expect(inputs()[0].placeholder).toBe('');
  });

  it('busca sem resultados mostra o motivo dado pela fonte, ligado ao campo', async () => {
    await searchFor(inputs()[0], '5299822');
    expect(hints()[0].textContent).toContain('CPF/CNPJ incompleto.');
    expect(inputs()[0].getAttribute('aria-describedby')).toBe(hints()[0].id);
  });

  it('sem motivo da fonte, mostra a mensagem padrão; com resultados, volta ao texto auxiliar', async () => {
    await searchFor(inputs()[1], 'Bruno');
    expect(hints()[1].textContent).toContain('Nenhum resultado para a busca.');
    await searchFor(inputs()[1], 'Ana');
    expect(hints()[1].textContent).toContain('Texto auxiliar.');
    expect(hints()[1].textContent).not.toContain('Nenhum resultado');
  });
});
