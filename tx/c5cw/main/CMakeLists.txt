# C5CW_BEACON (environment): the beacon directory (beacon.bin, beacon_cfg.h from tx/make_beacons.sh), set by flash.sh
set(beacon "$ENV{C5CW_BEACON}")
if(NOT beacon AND NOT CMAKE_BUILD_EARLY_EXPANSION)
    message(FATAL_ERROR "C5CW_BEACON not set: tx/c5cw/flash.sh BEACON_DIR [PORT]")
endif()
idf_component_register(SRCS "main.c"
                       INCLUDE_DIRS "${beacon}" "${CMAKE_CURRENT_LIST_DIR}/../../../third_party/ESP32-DATV-SA/firmware/main"
                       EMBED_FILES "${beacon}/beacon.bin"
                       REQUIRES esp_phy esp_wifi nvs_flash esp_timer esp_driver_gpio esp_hal_usb)
