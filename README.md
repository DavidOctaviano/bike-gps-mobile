# Bike GPS

Primeiro entregável do ecossistema Bike GPS: aplicativo Android nativo instalável, backend OAuth para Strava e receptor de rotas para ESP32-S3.

## Android

O aplicativo em `app/` oferece:

- painel de pedal em uma tela, com navegação central e velocidade média em destaque;
- mapa Mapbox com dados viários derivados do OpenStreetMap, marcador da localização precisa e câmera que acompanha o pedal;
- estilos Claro, Escuro e Satélite híbrido, cache local automático, POIs agrupados e heatmap MVT opcional;
- rota GPX offline de demonstração em Lagoa da Prata (MG), desenhada sobre o mapa real;
- gravação de distância e velocidade com GPS enquanto a tela está aberta;
- descoberta e conexão Bluetooth LE com estados visíveis;
- envio confiável de rota pelo Bike GPS Route Transfer Protocol v1;
- login Strava iniciado no backend, com ticket descartável no retorno ao app;
- listagem das rotas do atleta, download do GPX escolhido e exibição/envio dessa rota.

Compile com JDK 17, Android SDK 35 e Gradle 8.10.2:

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

O arquivo local será `app/build/outputs/apk/debug/app-debug.apk`. Para ativar o Mapbox e apontar o APK a um backend implantado, compile com:

```bash
./gradlew :app:assembleDebug \
  -PMAPBOX_ACCESS_TOKEN=<TOKEN_PUBLICO_PK> \
  -PBIKEGPS_API_BASE_URL=https://seu-backend.example
```

`MAPBOX_ACCESS_TOKEN` aceita exclusivamente um token público `pk.`. Nunca use um token secreto `sk.`. Sem a propriedade, o APK continua instalável e abre sem crash; o usuário pode tocar na área do mapa e informar seu token público no próprio aparelho. Ao tocar em **Strava**, também é possível informar a URL HTTPS do backend sem recompilar. O Mapbox conserva em cache local os recursos já visualizados; a rota demo e os dados GPX permanecem disponíveis no aplicativo.

Para a configuração completa dos mapas, OSM, cache, estilos, clustering e heatmap MVT, consulte [docs/maps.md](docs/maps.md).

## Backend OAuth do Strava

O backend requer Node.js 20 ou posterior e uma aplicação registrada em [Strava API Settings](https://www.strava.com/settings/api). Configure variáveis de ambiente apenas no cofre secreto da plataforma de implantação, nunca no repositório:

```env
STRAVA_CLIENT_ID=
STRAVA_CLIENT_SECRET=
STRAVA_CALLBACK_URL=https://seu-backend.example/oauth/strava/callback
APP_REDIRECT_URL=bikegps://oauth/strava
PORT=8080
```

O domínio de `STRAVA_CALLBACK_URL` deve ser exatamente o **Authorization Callback Domain** configurado no painel do Strava. Execute `npm run start:api` ou implante o `Dockerfile` em um serviço HTTPS. O fluxo usa `state` de uso único, troca o código somente no servidor e entrega ao aplicativo um ticket de 60 segundos. A sessão retornada ao Android contém os tokens Strava cifrados com AES-256-GCM; ela sobrevive a reinícios do backend e continua opaca para o aplicativo. O Android protege essa sessão novamente com Android Keystore.

O APK não pode funcionar com o Strava sem esses dados reais e sem um backend HTTPS acessível: o Strava exige o segredo na troca OAuth, e colocar esse segredo no aplicativo seria uma vulnerabilidade. Consulte [docs/strava-deployment.md](docs/strava-deployment.md).

## ESP32-S3

O firmware em `firmware/esp32-s3/` usa ESP-IDF/NimBLE, NVS e SPIFFS. Ele publica o serviço GATT Bike GPS, exige link BLE criptografado para escrita, conserva metadados e progresso para retomada e só promove a rota após conferir CRC32, tamanho e SHA-256. O botão **Enviar rota** procura esse receptor, conecta e transfere automaticamente. Se ele não estiver disponível, o app permite salvar o GPX para uso manual.

```bash
cd firmware/esp32-s3
idf.py set-target esp32s3
idf.py build
```

O layout fornecido pressupõe flash de 8 MB. Consulte [docs/ble-protocol.md](docs/ble-protocol.md) antes de integrar outro cliente ou firmware.

## Testes e APK no GitHub

`npm test` executa os testes do contrato e do OAuth. O workflow **Android APK** executa os testes Node e Android, compila, valida o ZIP do APK, calcula SHA-256 e publica o artefato `bike-gps-android-debug` em cada push para `main` ou execução manual. Configure o secret `MAPBOX_ACCESS_TOKEN` com um token público real `pk.` para que o APK publicado já saia com o mapa ativado. As configurações opcionais `BIKEGPS_ACTIVITY_TILES_URL` e `BIKEGPS_ACTIVITY_TILES_LAYER` são repository variables.

No GitHub, abra **Actions → Android APK → execução mais recente → Artifacts → bike-gps-android-debug**.

## Limite de compatibilidade

Os UUIDs e mensagens são um protocolo próprio do Bike GPS. Não há afirmação de compatibilidade direta com Garmin, Wahoo ou outros ecossistemas fechados; essa interoperabilidade depende de API/SDK oficial do fabricante.
