#include "ble_gatt_server.hpp"

#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "cJSON.h"
#include "esp_log.h"
#include "host/ble_gatt.h"
#include "host/ble_gap.h"
#include "host/ble_hs.h"
#include "host/ble_uuid.h"
#include "nimble/nimble_port.h"
#include "nimble/nimble_port_freertos.h"
#include "os/os_mbuf.h"
#include "route_transfer.hpp"
#include "services/gap/ble_svc_gap.h"
#include "services/gatt/ble_svc_gatt.h"
#include "store/config/ble_store_config.h"

namespace {
constexpr const char* kTag = "BIKE_GPS_BLE";
const ble_uuid128_t kServiceUuid = BLE_UUID128_INIT(0x01, 0x00, 0x34, 0x9a, 0x1b, 0x32, 0x62, 0x9b, 0x9a, 0x4c, 0x3d, 0x6a, 0x01, 0x00, 0x10, 0x7b);
const ble_uuid128_t kControlUuid = BLE_UUID128_INIT(0x01, 0x00, 0x34, 0x9a, 0x1b, 0x32, 0x62, 0x9b, 0x9a, 0x4c, 0x3d, 0x6a, 0x02, 0x00, 0x10, 0x7b);
const ble_uuid128_t kDataUuid = BLE_UUID128_INIT(0x01, 0x00, 0x34, 0x9a, 0x1b, 0x32, 0x62, 0x9b, 0x9a, 0x4c, 0x3d, 0x6a, 0x03, 0x00, 0x10, 0x7b);
const ble_uuid128_t kAckUuid = BLE_UUID128_INIT(0x01, 0x00, 0x34, 0x9a, 0x1b, 0x32, 0x62, 0x9b, 0x9a, 0x4c, 0x3d, 0x6a, 0x04, 0x00, 0x10, 0x7b);
const ble_uuid128_t kStatusUuid = BLE_UUID128_INIT(0x01, 0x00, 0x34, 0x9a, 0x1b, 0x32, 0x62, 0x9b, 0x9a, 0x4c, 0x3d, 0x6a, 0x05, 0x00, 0x10, 0x7b);

RouteTransfer route_transfer;
uint16_t connection_handle = BLE_HS_CONN_HANDLE_NONE;
uint16_t ack_value_handle;
uint16_t status_value_handle;
uint8_t own_address_type;

void advertise();

void notify(uint16_t attribute_handle, const std::string& json) {
  if (connection_handle == BLE_HS_CONN_HANDLE_NONE) return;
  os_mbuf* payload = ble_hs_mbuf_from_flat(json.data(), json.size());
  if (!payload) return;
  int result = ble_gatts_notify_custom(connection_handle, attribute_handle, payload);
  if (result != 0) ESP_LOGW(kTag, "notify failed: %d", result);
}

void status(const char* command, const std::string& suffix = "") {
  notify(status_value_handle, std::string("{\"command\":\"") + command + "\"" + suffix + "}");
}

const char* ack_name(AckStatus value) {
  switch (value) {
    case AckStatus::Ok: return "OK";
    case AckStatus::CrcError: return "CRC_ERROR";
    case AckStatus::OutOfOrder: return "OUT_OF_ORDER";
    case AckStatus::NoSpace: return "NO_SPACE";
  }
  return "OUT_OF_ORDER";
}

bool json_string(cJSON* root, const char* name, std::string* output) {
  cJSON* value = cJSON_GetObjectItemCaseSensitive(root, name);
  if (!cJSON_IsString(value) || !value->valuestring) return false;
  *output = value->valuestring;
  return true;
}

bool json_u32(cJSON* root, const char* name, uint32_t* output) {
  cJSON* value = cJSON_GetObjectItemCaseSensitive(root, name);
  if (!cJSON_IsNumber(value) || value->valuedouble < 0 || value->valuedouble > UINT32_MAX) return false;
  *output = static_cast<uint32_t>(value->valuedouble);
  return value->valuedouble == *output;
}

int handle_control(const std::vector<uint8_t>& bytes) {
  cJSON* root = cJSON_ParseWithLength(reinterpret_cast<const char*>(bytes.data()), bytes.size());
  if (!root) { status("ERROR", ",\"code\":\"CONTROL_JSON_INVALID\""); return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN; }
  std::string command;
  bool parsed = json_string(root, "command", &command);
  if (parsed && command == "START") {
    TransferManifest manifest;
    uint32_t version = 0;
    parsed = json_u32(root, "protocolVersion", &version) && version == 1
        && json_string(root, "transferId", &manifest.transfer_id)
        && json_string(root, "filename", &manifest.filename)
        && json_string(root, "format", &manifest.format)
        && json_u32(root, "fileSize", &manifest.file_size)
        && json_u32(root, "chunkSize", &manifest.chunk_size)
        && json_u32(root, "totalChunks", &manifest.total_chunks)
        && json_string(root, "sha256", &manifest.sha256_hex);
    if (parsed && route_transfer.start(manifest)) {
      status("READY", ",\"resumeFromSequence\":" + std::to_string(route_transfer.next_sequence()));
    } else {
      status("ERROR", ",\"code\":\"START_REJECTED\"");
    }
  } else if (parsed && command == "COMMIT") {
    std::string transfer_id;
    std::string hash;
    parsed = json_string(root, "transferId", &transfer_id) && json_string(root, "sha256", &hash)
        && route_transfer.matches(transfer_id, hash);
    if (parsed && route_transfer.commit()) status("TRANSFER_COMPLETE", ",\"sha256\":\"" + hash + "\"");
    else status("ERROR", ",\"code\":\"COMMIT_REJECTED\"");
  } else if (parsed && command == "CANCEL") {
    route_transfer.cancel();
    status("CANCELLED");
  } else {
    status("ERROR", ",\"code\":\"COMMAND_UNSUPPORTED\"");
  }
  cJSON_Delete(root);
  return parsed ? 0 : BLE_ATT_ERR_UNLIKELY;
}

int characteristic_access(uint16_t, uint16_t attribute_handle, ble_gatt_access_ctxt* context, void*) {
  if (context->op != BLE_GATT_ACCESS_OP_WRITE_CHR) return BLE_ATT_ERR_READ_NOT_PERMITTED;
  const uint16_t length = OS_MBUF_PKTLEN(context->om);
  std::vector<uint8_t> bytes(length);
  uint16_t copied = 0;
  if (ble_hs_mbuf_to_flat(context->om, bytes.data(), bytes.size(), &copied) != 0 || copied != length) {
    return BLE_ATT_ERR_UNLIKELY;
  }
  if (ble_uuid_cmp(context->chr->uuid, &kControlUuid.u) == 0) return handle_control(bytes);
  if (ble_uuid_cmp(context->chr->uuid, &kDataUuid.u) == 0) {
    uint32_t sequence = UINT32_MAX;
    AckStatus result = route_transfer.accept_packet(bytes.data(), bytes.size(), &sequence);
    notify(ack_value_handle, "{\"transferId\":\"" + route_transfer.transfer_id() + "\",\"sequence\":"
        + std::to_string(sequence) + ",\"status\":\"" + ack_name(result) + "\"}");
    return 0;
  }
  return BLE_ATT_ERR_UNLIKELY;
}

const ble_gatt_chr_def characteristics[] = {
    {.uuid = &kControlUuid.u, .access_cb = characteristic_access,
      .flags = BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_WRITE_ENC},
    {.uuid = &kDataUuid.u, .access_cb = characteristic_access,
      .flags = BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_WRITE_ENC},
    {.uuid = &kAckUuid.u, .flags = BLE_GATT_CHR_F_NOTIFY,
      .val_handle = &ack_value_handle},
    {.uuid = &kStatusUuid.u, .flags = BLE_GATT_CHR_F_NOTIFY,
      .val_handle = &status_value_handle},
    {0}
};

const ble_gatt_svc_def services[] = {{
  .type = BLE_GATT_SVC_TYPE_PRIMARY,
  .uuid = &kServiceUuid.u,
  .characteristics = characteristics
}, {0}};

int gap_event(ble_gap_event* event, void*) {
  switch (event->type) {
    case BLE_GAP_EVENT_CONNECT:
      if (event->connect.status == 0) {
        connection_handle = event->connect.conn_handle;
        ble_gap_security_initiate(connection_handle);
        ESP_LOGI(kTag, "connected");
      } else advertise();
      return 0;
    case BLE_GAP_EVENT_DISCONNECT:
      connection_handle = BLE_HS_CONN_HANDLE_NONE;
      ESP_LOGI(kTag, "disconnected; resumable transfer retained");
      advertise();
      return 0;
    case BLE_GAP_EVENT_ADV_COMPLETE:
      advertise();
      return 0;
    case BLE_GAP_EVENT_MTU:
      ESP_LOGI(kTag, "negotiated MTU: %d", event->mtu.value);
      return 0;
    default:
      return 0;
  }
}

void advertise() {
  ble_hs_adv_fields fields{};
  const char* name = "BikeGPS";
  fields.flags = BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP;
  fields.name = reinterpret_cast<const uint8_t*>(name);
  fields.name_len = strlen(name);
  fields.name_is_complete = 1;
  fields.uuids128 = const_cast<ble_uuid128_t*>(&kServiceUuid);
  fields.num_uuids128 = 1;
  fields.uuids128_is_complete = 1;
  int result = ble_gap_adv_set_fields(&fields);
  if (result != 0) { ESP_LOGE(kTag, "advertising fields failed: %d", result); return; }
  ble_gap_adv_params parameters{};
  parameters.conn_mode = BLE_GAP_CONN_MODE_UND;
  parameters.disc_mode = BLE_GAP_DISC_MODE_GEN;
  result = ble_gap_adv_start(own_address_type, nullptr, BLE_HS_FOREVER, &parameters, gap_event, nullptr);
  if (result != 0) ESP_LOGE(kTag, "advertising start failed: %d", result);
}

void on_sync() {
  if (ble_hs_id_infer_auto(0, &own_address_type) != 0) {
    ESP_LOGE(kTag, "could not infer BLE address");
    return;
  }
  advertise();
}

void host_task(void*) {
  nimble_port_run();
  nimble_port_freertos_deinit();
}
}

void bike_gps_ble_init() {
  ESP_ERROR_CHECK(nimble_port_init());
  ble_hs_cfg.sync_cb = on_sync;
  ble_hs_cfg.store_status_cb = ble_store_util_status_rr;
  ble_hs_cfg.sm_bonding = 1;
  ble_hs_cfg.sm_sc = 1;
  ble_hs_cfg.sm_io_cap = BLE_SM_IO_CAP_NO_IO;
  ble_svc_gap_init();
  ble_svc_gatt_init();
  ESP_ERROR_CHECK(ble_svc_gap_device_name_set("Bike GPS S3"));
  ESP_ERROR_CHECK(ble_gatts_count_cfg(services));
  ESP_ERROR_CHECK(ble_gatts_add_svcs(services));
  ble_store_config_init();
  nimble_port_freertos_init(host_task);
}
