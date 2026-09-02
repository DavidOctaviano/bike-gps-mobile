import type { Ack, TransferManifest } from "../../../../packages/contracts/src/ble";
import { crc32, encodePacket, routeChunkSize } from "./packet";

export interface BleTransport {
  connect(): Promise<void>;
  maximumWriteBytes(): Promise<number>;
  writeControl(message: object): Promise<void>;
  writeData(bytes: Uint8Array): Promise<void>;
  waitForAck(sequence: number, timeoutMs: number): Promise<Ack>;
  waitForReady(timeoutMs: number): Promise<{ resumeFromSequence: number }>;
  waitForComplete(timeoutMs: number): Promise<{ sha256: string }>;
}

type TransferInput = {
  transferId: string;
  filename: string;
  format: "GPX" | "FIT";
  bytes: Uint8Array;
  sha256: string;
};

export async function transferRoute(
  transport: BleTransport,
  input: TransferInput,
  onProgress: (value: number) => void,
  signal?: AbortSignal
) {
  await transport.connect();
  const chunkSize = routeChunkSize(await transport.maximumWriteBytes());
  const totalChunks = Math.ceil(input.bytes.length / chunkSize);
  const manifest: TransferManifest = {
    command: "START",
    protocolVersion: 1,
    transferId: input.transferId,
    filename: input.filename,
    format: input.format,
    fileSize: input.bytes.length,
    chunkSize,
    totalChunks,
    sha256: input.sha256
  };

  await transport.writeControl(manifest);
  let sequence = (await transport.waitForReady(5_000)).resumeFromSequence;

  while (sequence < totalChunks) {
    if (signal?.aborted) throw new Error("TRANSFER_CANCELLED");
    const payload = input.bytes.slice(sequence * chunkSize, (sequence + 1) * chunkSize);
    const packet = encodePacket(sequence, payload, crc32(payload));
    let confirmed = false;

    for (let attempt = 0; attempt < 3 && !confirmed; attempt++) {
      try {
        await transport.writeData(packet);
        const ack = await transport.waitForAck(sequence, 3_000);
        if (ack.status === "NO_SPACE") throw new Error("DEVICE_STORAGE_FULL");
        confirmed = ack.status === "OK";
      } catch (error) {
        if (error instanceof Error && error.message === "DEVICE_STORAGE_FULL") throw error;
        if (attempt === 2) throw error;
      }
    }
    if (!confirmed) throw new Error(`CHUNK_FAILED_${sequence}`);
    sequence += 1;
    onProgress(sequence / totalChunks);
  }

  await transport.writeControl({ command: "COMMIT", transferId: input.transferId, sha256: input.sha256 });
  const result = await transport.waitForComplete(15_000);
  if (result.sha256 !== input.sha256) throw new Error("FINAL_HASH_MISMATCH");
}
