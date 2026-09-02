# Bike GPS Route Transfer Protocol v1

Este contrato precisa ser implementado no aplicativo e no firmware.

## Ordem

1. Mobile conecta e descobre o serviço.
2. Mobile assina ACK e Status.
3. Mobile envia `START` pela característica Control.
4. Firmware reserva espaço e responde `READY`, incluindo `resumeFromSequence`.
5. Mobile envia os pacotes pela característica Data.
6. Firmware valida CRC32 e responde ACK para cada sequência.
7. Mobile envia `COMMIT`.
8. Firmware valida SHA-256 do arquivo e responde `TRANSFER_COMPLETE`.

## Recuperação

O firmware deve persistir `transferId`, hash, tamanho e último bloco confirmado. Uma nova conexão com o mesmo manifesto retoma a transferência. Um manifesto incompatível invalida a sessão anterior.

## Segurança

- Pareamento autenticado e bonding.
- Chave de sessão negociada após conexão.
- Proteção contra replay usando nonce/contador.
- Limite de tamanho e validação de nome/formato.
- Arquivo só é promovido para a pasta de rotas depois do SHA-256 final.

## Pacote Data v1

Todos os inteiros usam little-endian.

| Offset | Campo | Tamanho |
|---:|---|---:|
| 0 | Versão (`0x01`) | 1 |
| 1 | Flags | 1 |
| 2 | Sequência | 4 |
| 6 | Tamanho do payload | 2 |
| 8 | Payload | variável |
| final | CRC32 do payload | 4 |

O firmware rejeita pacote fora de ordem, CRC inválido, tamanho divergente e arquivo acima do limite. `COMMIT` só conclui depois de conferir tamanho, quantidade de blocos e SHA-256.

## Compatibilidade

Este é o protocolo real do ecossistema Bike GPS. Ele não declara compatibilidade com Garmin, Wahoo ou outros equipamentos fechados. A interoperabilidade depende de ambos os lados implementarem esta versão.
