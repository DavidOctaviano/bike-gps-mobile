#include "nvs_flash.h"
#include "esp_log.h"
#include "route_transfer.hpp"

// Integração NimBLE: registrar o serviço e encaminhar writes de Control/Data
// para RouteTransfer. O parser CBOR de Control e as notificações ACK ficam no
// adaptador GATT, isolados do mecanismo de persistência validado neste módulo.

extern "C" void app_main() {
  ESP_ERROR_CHECK(nvs_flash_init());
  ESP_LOGI("BIKE_GPS", "Route Transfer Protocol v1 ready");
  // init_filesystem();
  // init_nimble_gatt_server();
}

