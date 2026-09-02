#pragma once
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <string>

enum class AckStatus : uint8_t { Ok = 0, CrcError = 1, OutOfOrder = 2, NoSpace = 3 };

struct TransferManifest {
  std::string transfer_id;
  std::string filename;
  std::string format;
  std::string sha256_hex;
  uint32_t file_size;
  uint32_t chunk_size;
  uint32_t total_chunks;
};

class RouteTransfer {
 public:
  bool start(const TransferManifest& manifest);
  AckStatus accept_packet(const uint8_t* packet, size_t length, uint32_t* sequence);
  bool commit();
  void cancel();
  uint32_t next_sequence() const { return next_sequence_; }
  const std::string& transfer_id() const { return manifest_.transfer_id; }
  const std::string& sha256_hex() const { return manifest_.sha256_hex; }
  bool matches(const std::string& transfer_id, const std::string& sha256) const;

 private:
  TransferManifest manifest_{};
  FILE* staging_{nullptr};
  uint32_t next_sequence_{0};
  uint32_t received_bytes_{0};
  bool active_{false};
  static uint32_t crc32(const uint8_t* data, size_t length);
  bool verify_sha256();
  bool restore(const TransferManifest& manifest);
  bool persist_manifest();
  bool persist_progress();
  void clear_persisted();
};
