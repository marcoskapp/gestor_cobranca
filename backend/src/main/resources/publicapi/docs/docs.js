// Swagger UI da API pública: contrato publicado da v1, sem "Try it out" (evita uso de token em página aberta).
window.addEventListener('load', function () {
  window.ui = SwaggerUIBundle({
    url: '/public-api/v1/openapi.json',
    dom_id: '#swagger-ui',
    deepLinking: true,
    docExpansion: 'list',
    defaultModelsExpandDepth: 0,
    supportedSubmitMethods: [],
    validatorUrl: null,
  });
});
