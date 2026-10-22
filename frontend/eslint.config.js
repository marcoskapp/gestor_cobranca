// @ts-check
const eslint = require('@eslint/js');
const { defineConfig } = require('eslint/config');
const tseslint = require('typescript-eslint');
const angular = require('angular-eslint');

module.exports = defineConfig([
  {
    ignores: ['dist/**', '.angular/**', 'coverage/**'],
  },
  {
    files: ['**/*.ts'],
    extends: [
      eslint.configs.recommended,
      tseslint.configs.recommended,
      tseslint.configs.stylistic,
      angular.configs.tsRecommended,
    ],
    processor: angular.processInlineTemplates,
    rules: {
      '@angular-eslint/directive-selector': ['error', { type: 'attribute', prefix: 'app', style: 'camelCase' }],
      '@angular-eslint/component-selector': ['error', { type: 'element', prefix: 'app', style: 'kebab-case' }],
      '@angular-eslint/prefer-on-push-component-change-detection': 'off', // OnPush é o padrão no Angular 22
      '@angular-eslint/prefer-signals': 'error',
      '@typescript-eslint/no-unused-vars': ['error', { argsIgnorePattern: '^_', varsIgnorePattern: '^_' }],
      '@typescript-eslint/no-explicit-any': 'error',
      eqeqeq: ['error', 'always'],
      // dados pessoais nunca devem ir para o console
      'no-console': 'error',
      // segurança: nada de HTML vindo da API nem de bypass do sanitizador
      'no-restricted-syntax': [
        'error',
        {
          selector: "MemberExpression[property.name=/^(innerHTML|outerHTML)$/]",
          message: 'Não manipule innerHTML/outerHTML; use interpolação no template.',
        },
        {
          selector: "CallExpression[callee.property.name=/^bypassSecurityTrust/]",
          message: 'bypassSecurityTrust* é proibido nesta aplicação.',
        },
      ],
      'no-restricted-globals': [
        'error',
        { name: 'sessionStorage', message: 'Não use sessionStorage: tokens e dados pessoais ficam apenas em memória.' },
      ],
    },
  },
  {
    files: ['**/*.spec.ts'],
    rules: {
      '@typescript-eslint/no-non-null-assertion': 'off',
      // os testes verificam justamente que nada foi gravado no sessionStorage
      'no-restricted-globals': 'off',
    },
  },
  {
    files: ['**/*.html'],
    extends: [angular.configs.templateRecommended, angular.configs.templateAccessibility],
    rules: {
      '@angular-eslint/template/no-inline-styles': 'off',
      '@angular-eslint/template/prefer-control-flow': 'error',
      '@angular-eslint/template/eqeqeq': 'error',
      // $any() desliga a checagem de tipos dos templates
      '@angular-eslint/template/no-any': 'error',
    },
  },
]);
