# Bike GPS

Primeiro entregável do ecossistema Bike GPS: aplicativo Android nativo instalável, backend OAuth para Strava e receptor de rotas para ESP32-S3.

## Android

O aplicativo em `app/` oferece:

- painel de pedal em uma tela, com navegação central e velocidade média em destaque;
- mapa vetorial MapLibre/OpenFreeMap, marcador da localização precisa e câmera que acompanha o pedal;
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

O arquivo local será `app/build/outputs/apk/debug/app-debug.apk`. Para apontar o APK a um backend implantado, compile com:

```bash
./gradlew :app:assembleDebug -PBIKEGPS_API_BASE_URL=https://seu-backend.example
```

Sem essa propriedade, o APK continua instalável e todas as funções de mapa/GPS/BLE funcionam. Ao tocar em **Strava**, o usuário pode informar a URL HTTPS do backend no próprio aplicativo, sem recompilar. O mapa precisa de internet para baixar a cartografia; a rota demo e os dados GPX continuam disponíveis localmente depois de carregados.

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

`npm test` executa os testes do contrato e do OAuth. O workflow **Android APK** executa os testes Node e Android, compila, valida o ZIP do APK, calcula SHA-256 e publica o artefato `bike-gps-android-debug` em cada push para `main` ou execução manual.

No GitHub, abra **Actions → Android APK → execução mais recente → Artifacts → bike-gps-android-debug**.

## Limite de compatibilidade

Os UUIDs e mensagens são um protocolo próprio do Bike GPS. Não há afirmação de compatibilidade direta com Garmin, Wahoo ou outros ecossistemas fechados; essa interoperabilidade depende de API/SDK oficial do fabricante.
