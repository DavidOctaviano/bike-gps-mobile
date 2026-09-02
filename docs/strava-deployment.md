# Implantação segura do Strava

O Strava exige `client_id`, `client_secret` e uma URL HTTPS pública para concluir OAuth. O APK Bike GPS nunca recebe o segredo nem os tokens Strava em claro.

## 1. Registrar a aplicação

1. Abra `https://www.strava.com/settings/api` com a conta proprietária da integração.
2. Crie a aplicação e escolha um domínio HTTPS para o backend, por exemplo `api.exemplo.com`.
3. Em **Authorization Callback Domain**, informe apenas esse domínio, sem `https://` e sem caminho.
4. Não copie o `client_secret` para arquivos do Android, Gradle, GitHub Variables ou commits.

## 2. Implantar o container

O `Dockerfile` da raiz inicia a API na porta `8080`. Em Render, Railway, Fly.io, Cloud Run ou outro provedor de containers, configure no cofre de secrets:

```env
STRAVA_CLIENT_ID=<id real da aplicação>
STRAVA_CLIENT_SECRET=<segredo real, somente no cofre do servidor>
STRAVA_CALLBACK_URL=https://api.exemplo.com/oauth/strava/callback
APP_REDIRECT_URL=bikegps://oauth/strava
PORT=8080
```

Depois da implantação, `GET https://api.exemplo.com/health` deve responder `{"ok":true}`. TLS deve ser válido e não pode haver redirecionamento para HTTP.

## 3. Configurar o Android

Há duas opções:

- toque em **Strava** no app e informe `https://api.exemplo.com`; ou
- defina a variável de repositório `BIKEGPS_API_BASE_URL` no GitHub Actions para o APK já sair com esse endereço como padrão.

Ao conectar, autorize o escopo `read_all`, necessário para listar e exportar rotas privadas. O app recebe um ticket de uso único, guarda a sessão opaca com Android Keystore, lista as rotas do atleta e baixa o GPX escolhido pelo backend.

## Diagnóstico

- `STRAVA_SCOPE_READ_ALL_REQUIRED`: desconecte a aplicação no Strava e autorize novamente marcando leitura de dados privados.
- `OAUTH_STATE_INVALID`: o fluxo expirou ou foi aberto duas vezes; reinicie pelo botão Strava.
- `SESSION_INVALID`: o segredo da aplicação mudou ou a sessão venceu; conecte novamente.
- `STRAVA_ROUTES_4xx`: confirme o atleta, os escopos e os limites da API no painel Strava.
