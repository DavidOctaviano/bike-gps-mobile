#include "route_transfer.hpp"

#include <algorithm>
#include <cstring>
#include <sys/stat.h>

#include "esp_spiffs.h"
#include "mbedtls/sha256.h"
#include "nvs.h"

namespace {
constexpr size_t kHeader = 8;
constexpr size_t kCrc = 4;
constexpr const char* kStagingPath = "/spiffs/incoming.part";
constexpr const char* kCurrentGpxPath = "/spiffs/current.gpx";
constexpr const char* kCurrentFitPath = "/spiffs/current.fit";

uint16_t read_u16_le(const uint8_t* p) { return uint16_t(p[0]) | (uint16_t(p[1]) << 8); }
uint32_t read_u32_le(const uint8_t* p) {
  return uint32_t(p[0]) | (uint32_t(p[1]) << 8) | (uint32_t(p[2]) << 16) | (uint32_t(p[3]) << 24);
}

bool valid_hex_hash(const std::string& value) {
  if (value.size() != 64) return false;
  for (char character : value) {
    if (!((character >= '0' && character <= '9') || (character >= 'a' && character <= 'f'))) return false;
  }
  return true;
}

bool valid_filename(const std::string& value) {
  if (value.empty() || value.size() > 96 || value == "." || value == "..") return false;
  for (char character : value) {
    const bool safe = (character >= 'a' && character <= 'z') || (character >= 'A' && character <= 'Z')
        || (character >= '0' && character <= '9') || character == '.' || character == '-' || character == '_';
    if (!safe) return false;
  }
  return true;
}

bool valid_transfer_id(const std::string& value) {
  if (value.empty() || value.size() > 64) return false;
  for (char character : value) {
    const bool safe = (character >= 'a' && character <= 'z') || (character >= 'A' && character <= 'Z')
        || (character >= '0' && character <= '9') || character == '-';
    if (!safe) return false;
  }
  return true;
}

bool get_string(nvs_handle_t handle, const char* key, std::string* output) {
  size_t size = 0;
  if (nvs_get_str(handle, key, nullptr, &size) != ESP_OK || size == 0) return false;
  std::string value(size, '\0');
  if (nvs_get_str(handle, key, value.data(), &size) != ESP_OK) return false;
  value.resize(size - 1);
  *output = value;
  return true;
}
}

bool RouteTransfer::start(const TransferManifest& manifest) {
  const bool metadata_valid = manifest.file_size > 0 && manifest.file_size <= 16 * 1024 * 1024
      && manifest.chunk_size > 0 && manifest.chunk_size <= 505 && manifest.total_chunks > 0
      && manifest.total_chunks == (manifest.file_size + manifest.chunk_size - 1) / manifest.chunk_size
      && (manifest.format == "GPX" || manifest.format == "FIT")
      && valid_transfer_id(manifest.transfer_id) && valid_filename(manifest.filename)
      && valid_hex_hash(manifest.sha256_hex);
  if (!metadata_valid) return false;
  if (active_ && matches(manifest.transfer_id, manifest.sha256_hex)
      && manifest.chunk_size == manifest_.chunk_size && manifest.file_size == manifest_.file_size) return true;

  if (staging_) fclose(staging_);
  staging_ = nullptr;
  active_ = false;
  if (restore(manifest)) return true;

  clear_persisted();
  remove(kStagingPath);
  size_t filesystem_total = 0;
  size_t filesystem_used = 0;
  if (esp_spiffs_info(nullptr, &filesystem_total, &filesystem_used) != ESP_OK
      || filesystem_used > filesystem_total || manifest.file_size > filesystem_total - filesystem_used) return false;
  manifest_ = manifest;
  staging_ = fopen(kStagingPath, "w+b");
  active_ = staging_ != nullptr;
  next_sequence_ = 0;
  received_bytes_ = 0;
  if (!active_ || !persist_manifest() || !persist_progress()) {
    cancel();
    return false;
  }
  return true;
}

AckStatus RouteTransfer::accept_packet(const uint8_t* packet, size_t length, uint32_t* sequence) {
  if (!active_ || length < kHeader + kCrc || packet[0] != 1) return AckStatus::OutOfOrder;
  const uint32_t seq = read_u32_le(packet + 2);
  const uint16_t payload_length = read_u16_le(packet + 6);
  *sequence = seq;
  if (seq >= manifest_.total_chunks) return AckStatus::OutOfOrder;
  const uint32_t expected_length = seq + 1 == manifest_.total_chunks
      ? manifest_.file_size - seq * manifest_.chunk_size : manifest_.chunk_size;
  if (payload_length != expected_length || length != kHeader + payload_length + kCrc) return AckStatus::OutOfOrder;
  const uint8_t* payload = packet + kHeader;
  const uint32_t supplied_crc = read_u32_le(payload + payload_length);
  if (crc32(payload, payload_length) != supplied_crc) return AckStatus::CrcError;
  // If the previous ACK was lost, acknowledge the last persisted packet without writing it twice.
  if (next_sequence_ > 0 && seq == next_sequence_ - 1) return AckStatus::Ok;
  if (seq != next_sequence_) return AckStatus::OutOfOrder;
  if (received_bytes_ + payload_length > manifest_.file_size) return AckStatus::NoSpace;
  if (fwrite(payload, 1, payload_length, staging_) != payload_length || fflush(staging_) != 0) return AckStatus::NoSpace;
  received_bytes_ += payload_length;
  next_sequence_ += 1;
  if (!persist_progress()) return AckStatus::NoSpace;
  return AckStatus::Ok;
}

bool RouteTransfer::commit() {
  if (!active_ || received_bytes_ != manifest_.file_size || next_sequence_ != manifest_.total_chunks) return false;
  if (fflush(staging_) != 0 || !verify_sha256()) return false;
  fclose(staging_);
  staging_ = nullptr;
  const char* destination = manifest_.format == "FIT" ? kCurrentFitPath : kCurrentGpxPath;
  remove(destination);
  if (rename(kStagingPath, destination) != 0) return false;
  active_ = false;
  clear_persisted();
  return true;
}

void RouteTransfer::cancel() {
  if (staging_) fclose(staging_);
  staging_ = nullptr;
  active_ = false;
  next_sequence_ = 0;
  received_bytes_ = 0;
  remove(kStagingPath);
  clear_persisted();
}

bool RouteTransfer::matches(const std::string& transfer_id, const std::string& sha256) const {
  return active_ && manifest_.transfer_id == transfer_id && manifest_.sha256_hex == sha256;
}

bool RouteTransfer::restore(const TransferManifest& requested) {
  nvs_handle_t handle;
  if (nvs_open("route_xfer", NVS_READONLY, &handle) != ESP_OK) return false;
  TransferManifest stored;
  bool ok = get_string(handle, "id", &stored.transfer_id) && get_string(handle, "name", &stored.filename)
      && get_string(handle, "format", &stored.format) && get_string(handle, "sha", &stored.sha256_hex)
      && nvs_get_u32(handle, "size", &stored.file_size) == ESP_OK
      && nvs_get_u32(handle, "chunk", &stored.chunk_size) == ESP_OK
      && nvs_get_u32(handle, "total", &stored.total_chunks) == ESP_OK
      && nvs_get_u32(handle, "next", &next_sequence_) == ESP_OK
      && nvs_get_u32(handle, "bytes", &received_bytes_) == ESP_OK;
  nvs_close(handle);
  ok = ok && stored.transfer_id == requested.transfer_id && stored.filename == requested.filename
      && stored.format == requested.format && stored.sha256_hex == requested.sha256_hex
      && stored.file_size == requested.file_size && stored.chunk_size == requested.chunk_size
      && stored.total_chunks == requested.total_chunks && next_sequence_ <= stored.total_chunks
      && received_bytes_ == std::min(stored.file_size, next_sequence_ * stored.chunk_size);
  struct stat info{};
  ok = ok && stat(kStagingPath, &info) == 0 && static_cast<uint32_t>(info.st_size) == received_bytes_;
  if (!ok) return false;
  staging_ = fopen(kStagingPath, "r+b");
  if (!staging_ || fseek(staging_, received_bytes_, SEEK_SET) != 0) {
    if (staging_) fclose(staging_);
    staging_ = nullptr;
    return false;
  }
  manifest_ = stored;
  active_ = true;
  return true;
}

bool RouteTransfer::persist_manifest() {
  nvs_handle_t handle;
  if (nvs_open("route_xfer", NVS_READWRITE, &handle) != ESP_OK) return false;
  bool ok = nvs_set_str(handle, "id", manifest_.transfer_id.c_str()) == ESP_OK
      && nvs_set_str(handle, "name", manifest_.filename.c_str()) == ESP_OK
      && nvs_set_str(handle, "format", manifest_.format.c_str()) == ESP_OK
      && nvs_set_str(handle, "sha", manifest_.sha256_hex.c_str()) == ESP_OK
      && nvs_set_u32(handle, "size", manifest_.file_size) == ESP_OK
      && nvs_set_u32(handle, "chunk", manifest_.chunk_size) == ESP_OK
      && nvs_set_u32(handle, "total", manifest_.total_chunks) == ESP_OK
      && nvs_commit(handle) == ESP_OK;
  nvs_close(handle);
  return ok;
}

bool RouteTransfer::persist_progress() {
  nvs_handle_t handle;
  if (nvs_open("route_xfer", NVS_READWRITE, &handle) != ESP_OK) return false;
  bool ok = nvs_set_u32(handle, "next", next_sequence_) == ESP_OK
      && nvs_set_u32(handle, "bytes", received_bytes_) == ESP_OK && nvs_commit(handle) == ESP_OK;
  nvs_close(handle);
  return ok;
}

void RouteTransfer::clear_persisted() {
  nvs_handle_t handle;
  if (nvs_open("route_xfer", NVS_READWRITE, &handle) == ESP_OK) {
    nvs_erase_all(handle);
    nvs_commit(handle);
    nvs_close(handle);
  }
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
  if (mbedtls_sha256_starts(&ctx, 0) != 0) { mbedtls_sha256_free(&ctx); return false; }
  uint8_t buffer[1024];
  size_t count;
  while ((count = fread(buffer, 1, sizeof(buffer), staging_)) > 0) {
    if (mbedtls_sha256_update(&ctx, buffer, count) != 0) { mbedtls_sha256_free(&ctx); return false; }
  }
  uint8_t digest[32];
  if (mbedtls_sha256_finish(&ctx, digest) != 0) { mbedtls_sha256_free(&ctx); return false; }
  mbedtls_sha256_free(&ctx);
  static const char* hex = "0123456789abcdef";
  std::string actual;
  actual.reserve(64);
  for (uint8_t byte : digest) { actual += hex[byte >> 4]; actual += hex[byte & 0x0f]; }
  return actual == manifest_.sha256_hex;
}
