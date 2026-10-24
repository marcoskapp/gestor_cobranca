import { maskDocument, validDocument } from './portal-flow';

describe('documento do devedor no portal', () => {
  it('mascara CPF e CNPJ enquanto digita', () => {
    expect(maskDocument('52998224725')).toBe('529.982.247-25');
    expect(maskDocument('529982')).toBe('529.982');
    expect(maskDocument('11444777000161')).toBe('11.444.777/0001-61');
    expect(maskDocument('abc529')).toBe('529');
  });

  it('confere os dígitos verificadores', () => {
    expect(validDocument('529.982.247-25')).toBe(true);
    expect(validDocument('529.982.247-24')).toBe(false);
    expect(validDocument('111.111.111-11')).toBe(false);
    expect(validDocument('11.444.777/0001-61')).toBe(true);
    expect(validDocument('11.444.777/0001-62')).toBe(false);
    expect(validDocument('123')).toBe(false);
  });
});
