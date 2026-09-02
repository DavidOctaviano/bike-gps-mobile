export const BIKE_GPS_GATT = {
  service: "7B100001-6A3D-4C9A-9B62-321B9A340001",
  control: "7B100002-6A3D-4C9A-9B62-321B9A340001",
  data: "7B100003-6A3D-4C9A-9B62-321B9A340001",
  ack: "7B100004-6A3D-4C9A-9B62-321B9A340001",
  status: "7B100005-6A3D-4C9A-9B62-321B9A340001"
} as const;

export type AckStatus = "OK" | "CRC_ERROR" | "OUT_OF_ORDER" | "NO_SPACE";

export type Ack = {
  transferId: string;
  sequence: number;
  status: AckStatus;
};

export type TransferManifest = {
  command: "START";
  protocolVersion: 1;
  transferId: string;
  filename: string;
  format: "GPX" | "FIT";
  fileSize: number;
  totalChunks: number;
  sha256: string;
};

