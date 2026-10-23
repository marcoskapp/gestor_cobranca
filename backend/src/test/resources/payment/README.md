Certificado PKCS#12 autoassinado usado **somente** pelos testes do módulo payment (mTLS do adaptador
Pix e conferência de impressão digital no webhook). Não é aceito por nenhum PSP e não deve ser usado
fora dos testes. Certificados reais ficam cifrados por tenant (`payment.pix_account`).
