/** Item de seleção genérico (id + rótulo). */
export interface Option<T = string> {
  value: T;
  label: string;
}
