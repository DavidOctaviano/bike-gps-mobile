# Bike GPS Companion

Base de engenharia para o aplicativo companion Android/iOS e para a API que integra o Strava.

## Gerar o APK

Abra **Actions > Gerar APK Android > Run workflow**. Ao concluir, baixe o artefato `bike-gps-android`. O APK de depuração pode ser instalado diretamente no Android autorizando instalação de fontes desconhecidas.

## Estado desta entrega

- Contrato OAuth seguro: o segredo e os refresh tokens do Strava ficam no backend.
- Catálogo e exportação GPX de rotas do Strava.
- Protocolo GATT versionado para firmware próprio.
- Transferência BLE com fragmentação, ACK, repetição e retomada.
- Adaptador BLE simulado para testes sem hardware.
- Receptor ESP-IDF para ESP32-S3 com validação binária, CRC32, persistência temporária e SHA-256.

## Limites objetivos

Os UUIDs deste projeto são um contrato proposto. Um ciclocomputador Garmin não aceitará esse protocolo sem API/SDK oficial do fabricante. Para hardware Bike GPS, o firmware deve implementar `docs/ble-protocol.md`.

## Aplicativo instalável

O Android deve ser compilado com Android Studio/JDK e assinado para distribuição. O iOS exige macOS, Xcode e conta Apple Developer. Nenhum segredo real deve ser commitado.

## Variáveis do backend

```env
STRAVA_CLIENT_ID=
STRAVA_CLIENT_SECRET=
API_URL=https://api.exemplo.com
APP_REDIRECT_URL=bikegps://oauth/strava
TOKEN_ENCRYPTION_KEY=
```
