idf_component_register(SRCS "main.cpp" "route_transfer.cpp"
                       INCLUDE_DIRS "."
                       REQUIRES bt nvs_flash mbedtls fatfs)

