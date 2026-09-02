# Bike GPS Route Transfer Protocol v1

Contrato entre o aplicativo e o firmware próprio Bike GPS. Inteiros binários usam little-endian e mensagens de controle usam JSON UTF-8 compacto.

## Serviço GATT

| Papel | UUID | Propriedades |
|---|---|---|
| Serviço | `7b100001-6a3d-4c9a-9b62-321b9a340001` | primário |
| Control | `7b100002-6a3d-4c9a-9b62-321b9a340001` | write, link criptografado |
| Data | `7b100003-6a3d-4c9a-9b62-321b9a340001` | write, link criptografado |
| ACK | `7b100004-6a3d-4c9a-9b62-321b9a340001` | notify |
| Status | `7b100005-6a3d-4c9a-9b62-321b9a340001` | notify |

O cliente solicita MTU 517 e aceita o valor negociado pelo periférico. `maximumWriteBytes = MTU - 3` e `chunkSize = maximumWriteBytes - 12`. O manifesto registra o `chunkSize`, tornando a retomada determinística quando a reconexão negocia o mesmo MTU; com outro tamanho, a transferência reinicia com segurança.

## Sequência

1. O mobile conecta, descobre o serviço e assina ACK e Status.
2. O mobile negocia MTU e envia `START` por Control.
3. O firmware valida formato/espaço, cria ou recupera o staging e responde `READY`.
4. O mobile envia um pacote por vez em Data e aguarda o ACK da mesma sequência.
5. CRC inválido, ACK negativo ou timeout causam repetição, no máximo três tentativas.
6. Depois do último ACK, o mobile envia `COMMIT`.
7. O firmware confere tamanho, quantidade de blocos e SHA-256, move atomicamente o staging e responde `TRANSFER_COMPLETE`.

## Mensagens Control e Status

```json
{"command":"START","protocolVersion":1,"transferId":"id-estavel","filename":"rota.gpx","format":"GPX","fileSize":1234,"chunkSize":232,"totalChunks":6,"sha256":"hexadecimal-de-64-caracteres"}
```

```json
{"command":"READY","resumeFromSequence":2}
```

```json
{"command":"COMMIT","transferId":"id-estavel","sha256":"hexadecimal-de-64-caracteres"}
```

```json
{"command":"TRANSFER_COMPLETE","sha256":"hexadecimal-de-64-caracteres"}
```

Erros de controle usam `{"command":"ERROR","code":"CODIGO"}`. `CANCEL` remove a sessão parcial; desconexão simples a preserva.

## Pacote Data v1

| Offset | Campo | Tamanho |
|---:|---|---:|
| 0 | versão (`0x01`) | 1 byte |
| 1 | flags (`0x00`) | 1 byte |
| 2 | sequência | 4 bytes |
| 6 | tamanho do payload | 2 bytes |
| 8 | payload | variável |
| final | CRC32 IEEE do payload | 4 bytes |

ACK é JSON UTF-8: `{"transferId":"id-estavel","sequence":2,"status":"OK"}`. Estados permitidos: `OK`, `CRC_ERROR`, `OUT_OF_ORDER` e `NO_SPACE`.

## Retomada e integridade

O firmware persiste em NVS `transferId`, nome, formato, hash, tamanho, chunk, total, próximo bloco e bytes recebidos. O staging fica em SPIFFS. Um `START` idêntico reabre o arquivo na posição confirmada; metadados incompatíveis invalidam o staging anterior. O cliente deve reutilizar o mesmo `transferId` para o mesmo conteúdo.

CRC32 detecta corrupção por pacote. SHA-256 autentica a reconstrução completa antes da promoção para `current.gpx` ou `current.fit`.

## Segurança e compatibilidade

As características de escrita exigem criptografia BLE e o firmware solicita Secure Connections com bonding. Hardware de produção deve escolher um método de pareamento autenticado compatível com sua interface física; o perfil de referência sem teclado/tela usa `NoInputNoOutput` e, portanto, não oferece MITM autenticado. CRC32 e SHA-256 fornecem integridade, não sigilo por si sós.

Este protocolo é próprio do Bike GPS. Ele não declara compatibilidade direta com Garmin ou outros equipamentos fechados sem API/SDK oficial.
