#include "ble_gatt_server.hpp"

#include "esp_err.h"
#include "esp_log.h"
#include "esp_spiffs.h"
#include "nvs_flash.h"

namespace {
constexpr const char* kTag = "BIKE_GPS";

void initialize_nvs() {
  esp_err_t result = nvs_flash_init();
  if (result == ESP_ERR_NVS_NO_FREE_PAGES || result == ESP_ERR_NVS_NEW_VERSION_FOUND) {
    ESP_ERROR_CHECK(nvs_flash_erase());
    result = nvs_flash_init();
  }
  ESP_ERROR_CHECK(result);
}

void initialize_filesystem() {
  esp_vfs_spiffs_conf_t config{};
  config.base_path = "/spiffs";
  config.partition_label = nullptr;
  config.max_files = 4;
  config.format_if_mount_failed = true;
  ESP_ERROR_CHECK(esp_vfs_spiffs_register(&config));
  size_t total = 0;
  size_t used = 0;
  ESP_ERROR_CHECK(esp_spiffs_info(nullptr, &total, &used));
  ESP_LOGI(kTag, "SPIFFS ready: %u/%u bytes used", static_cast<unsigned>(used), static_cast<unsigned>(total));
}
}

extern "C" void app_main() {
  initialize_nvs();
  initialize_filesystem();
  bike_gps_ble_init();
  ESP_LOGI(kTag, "Bike GPS Route Transfer Protocol v1 advertising");
}
