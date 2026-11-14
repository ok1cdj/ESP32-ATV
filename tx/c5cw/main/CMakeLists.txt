# C5CW_BEACON (environment): the beacon directory (beacon.bin, beacon_cfg.h from tx/make_beacons.sh), set by flash.sh; empty: no
# built-in beacon (the web build, tx/c5cw/build_web.sh) - the transmitter then plays only a loop stored with VIDW
set(beacon "$ENV{C5CW_BEACON}")
# the receiver in the same image (rx/c5rx/main/main.c with C5_TRX: c5rx_trx.h instead of its app_main)
set(rx_src "${CMAKE_CURRENT_LIST_DIR}/../../../rx/c5rx/main/main.c")
set(inc "${CMAKE_CURRENT_LIST_DIR}/../../common"                    # rrc_tables.h, frac_clock.h
        "${CMAKE_CURRENT_LIST_DIR}/../../../rx/c5rx/main")   # c5_led.h
if(beacon)
    idf_component_register(SRCS "main.c" ${rx_src}
                           INCLUDE_DIRS "${beacon}" ${inc}
                           EMBED_FILES "${beacon}/beacon.bin"
                           REQUIRES esp_phy esp_wifi nvs_flash esp_timer esp_driver_gpio esp_hal_usb esp_partition esp_hal_mspi spi_flash esp_app_format)
else()
    idf_component_register(SRCS "main.c" ${rx_src}
                           INCLUDE_DIRS ${inc}
                           REQUIRES esp_phy esp_wifi nvs_flash esp_timer esp_driver_gpio esp_hal_usb esp_partition esp_hal_mspi spi_flash esp_app_format)
    target_compile_definitions(${COMPONENT_LIB} PRIVATE C5CW_NO_BEACON)
endif()
if(NOT CMAKE_BUILD_EARLY_EXPANSION)                                # not in IDF's script pass
    set_source_files_properties(${rx_src} PROPERTIES COMPILE_DEFINITIONS C5_TRX)
endif()
