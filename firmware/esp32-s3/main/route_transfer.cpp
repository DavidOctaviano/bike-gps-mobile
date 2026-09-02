#include "route_transfer.hpp"
#include <cstring>
#include "mbedtls/sha256.h"

namespace {
constexpr size_t kHeader = 8;
constexpr size_t kCrc = 4;
uint16_t read_u16_le(const uint8_t* p) { return uint16_t(p[0]) | (uint16_t(p[1]) << 8); }
uint32_t read_u32_le(const uint8_t* p) {
  return uint32_t(p[0]) | (uint32_t(p[1]) << 8) | (uint32_t(p[2]) << 16) | (uint32_t(p[3]) << 24);
}
}

bool RouteTransfer::start(const TransferManifest& manifest) {
  if (active_ && manifest.transfer_id == manifest_.transfer_id &&
      manifest.sha256_hex == manifest_.sha256_hex) return true;
  cancel();
  if (manifest.file_size == 0 || manifest.file_size > 16 * 1024 * 1024 || manifest.total_chunks == 0) return false;
  manifest_ = manifest;
  staging_ = fopen("/routes/incoming.part", "wb");
  active_ = staging_ != nullptr;
  next_sequence_ = 0;
  received_bytes_ = 0;
  return active_;
}

AckStatus RouteTransfer::accept_packet(const uint8_t* packet, size_t length, uint32_t* sequence) {
  if (!active_ || length < kHeader + kCrc || packet[0] != 1) return AckStatus::OutOfOrder;
  const uint32_t seq = read_u32_le(packet + 2);
  const uint16_t payload_length = read_u16_le(packet + 6);
  *sequence = seq;
  if (seq != next_sequence_ || length != kHeader + payload_length + kCrc) return AckStatus::OutOfOrder;
  const uint8_t* payload = packet + kHeader;
  const uint32_t supplied_crc = read_u32_le(payload + payload_length);
  if (crc32(payload, payload_length) != supplied_crc) return AckStatus::CrcError;
  if (received_bytes_ + payload_length > manifest_.file_size) return AckStatus::NoSpace;
  if (fwrite(payload, 1, payload_length, staging_) != payload_length) return AckStatus::NoSpace;
  fflush(staging_);
  received_bytes_ += payload_length;
  next_sequence_ += 1;
  return AckStatus::Ok;
}

bool RouteTransfer::commit() {
  if (!active_ || received_bytes_ != manifest_.file_size || next_sequence_ != manifest_.total_chunks) return false;
  fflush(staging_);
  if (!verify_sha256()) return false;
  fclose(staging_);
  staging_ = nullptr;
  remove("/routes/current.gpx");
  if (rename("/routes/incoming.part", "/routes/current.gpx") != 0) return false;
  active_ = false;
  return true;
}

void RouteTransfer::cancel() {
  if (staging_) fclose(staging_);
  staging_ = nullptr;
  active_ = false;
  next_sequence_ = 0;
  received_bytes_ = 0;
  remove("/routes/incoming.part");
}

uint32_t RouteTransfer::crc32(const uint8_t* data, size_t length) {
  uint32_t crc = 0xffffffff;
  for (size_t i = 0; i < length; ++i) {
    crc ^= data[i];
    for (int bit = 0; bit < 8; ++bit) crc = (crc >> 1) ^ (0xedb88320 & -(crc & 1));
  }
  return crc ^ 0xffffffff;
}

bool RouteTransfer::verify_sha256() {
  if (fseek(staging_, 0, SEEK_SET) != 0) return false;
  mbedtls_sha256_context ctx;
  mbedtls_sha256_init(&ctx);
  mbedtls_sha256_starts(&ctx, 0);
  uint8_t buffer[1024];
  size_t count;
  while ((count = fread(buffer, 1, sizeof(buffer), staging_)) > 0) mbedtls_sha256_update(&ctx, buffer, count);
  uint8_t digest[32];
  mbedtls_sha256_finish(&ctx, digest);
  mbedtls_sha256_free(&ctx);
  static const char* hex = "0123456789abcdef";
  std::string actual;
  actual.reserve(64);
  for (uint8_t byte : digest) { actual += hex[byte >> 4]; actual += hex[byte & 0x0f]; }
  return actual == manifest_.sha256_hex;
}

