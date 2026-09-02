const HEADER_BYTES = 8;
const CRC_BYTES = 4;

export const routeChunkSize = (maximumWriteBytes: number) => {
  const size = maximumWriteBytes - HEADER_BYTES - CRC_BYTES;
  if (size < 1) throw new Error("NEGOTIATED_PAYLOAD_TOO_SMALL");
  return size;
};

export function encodePacket(sequence: number, payload: Uint8Array, crc32: number) {
  const packet = new Uint8Array(HEADER_BYTES + payload.length + CRC_BYTES);
  const view = new DataView(packet.buffer);
  view.setUint8(0, 1);
  view.setUint8(1, 0);
  view.setUint32(2, sequence, true);
  view.setUint16(6, payload.length, true);
  packet.set(payload, HEADER_BYTES);
  view.setUint32(HEADER_BYTES + payload.length, crc32 >>> 0, true);
  return packet;
}

export function crc32(bytes: Uint8Array) {
  let crc = 0xffffffff;
  for (const byte of bytes) {
    crc ^= byte;
    for (let bit = 0; bit < 8; bit++) {
      crc = (crc >>> 1) ^ (0xedb88320 & -(crc & 1));
    }
  }
  return (crc ^ 0xffffffff) >>> 0;
}

