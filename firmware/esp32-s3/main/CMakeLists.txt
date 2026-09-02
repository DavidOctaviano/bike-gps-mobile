idf_component_register(SRCS "main.cpp" "route_transfer.cpp" "ble_gatt_server.cpp"
                       INCLUDE_DIRS "."
                       REQUIRES bt nvs_flash mbedtls spiffs json)
