# C5CW_BEACON (environment): the beacon directory (beacon.bin, beacon_cfg.h from tx/make_beacons.sh), set by flash.sh; empty: no
# built-in beacon (the web build, tx/c5cw/build_web.sh) - the transmitter then plays only a loop stored with VIDW
set(beacon "$ENV{C5CW_BEACON}")
set(inc "${CMAKE_CURRENT_LIST_DIR}/../../../third_party/ESP32-DATV-SA/firmware/main"
        "${CMAKE_CURRENT_LIST_DIR}/../../../rx/c5rx/main")   # c5_led.h
if(beacon)
    idf_component_register(SRCS "main.c"
                           INCLUDE_DIRS "${beacon}" ${inc}
                           EMBED_FILES "${beacon}/beacon.bin"
                           REQUIRES esp_phy esp_wifi nvs_flash esp_timer esp_driver_gpio esp_hal_usb esp_partition esp_hal_mspi spi_flash esp_app_format)
else()
    idf_component_register(SRCS "main.c"
                           INCLUDE_DIRS ${inc}
                           REQUIRES esp_phy esp_wifi nvs_flash esp_timer esp_driver_gpio esp_hal_usb esp_partition esp_hal_mspi spi_flash esp_app_format)
    target_compile_definitions(${COMPONENT_LIB} PRIVATE C5CW_NO_BEACON)
endif()
