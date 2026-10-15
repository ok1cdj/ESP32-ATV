package com.ok1cdj.atvrx

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbRequest
import java.io.IOException
import java.nio.ByteBuffer

/**
 * CDC-ACM port of the ESP32-S3 native USB Serial/JTAG (303a:1001), straight on the USB host API.
 * Leave DTR/RTS both asserted: DTR=0 with RTS=1 resets the chip into the ROM loader (see espdr nb.py open_link).
 */
class UsbCdc(private val conn: UsbDeviceConnection, device: UsbDevice) {
    private val comm: UsbInterface
    private val data: UsbInterface
    private val epIn: UsbEndpoint
    private val epOut: UsbEndpoint

    init {
        val ifaces = (0 until device.interfaceCount).map { device.getInterface(it) }
        comm = ifaces.firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_COMM }
            ?: throw IOException("no CDC control interface")
        data = ifaces.firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA }
            ?: throw IOException("no CDC data interface")
        val eps = (0 until data.endpointCount).map { data.getEndpoint(it) }
            .filter { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
        epIn = eps.firstOrNull { it.direction == UsbConstants.USB_DIR_IN } ?: throw IOException("no bulk IN endpoint")
        epOut = eps.firstOrNull { it.direction == UsbConstants.USB_DIR_OUT } ?: throw IOException("no bulk OUT endpoint")
        if (!conn.claimInterface(comm, true) || !conn.claimInterface(data, true)) throw IOException("cannot claim the interfaces")
        // SET_CONTROL_LINE_STATE: DTR | RTS together, as Linux and pyserial do on open
        if (conn.controlTransfer(0x21, 0x22, 0x03, comm.id, null, 0, 1000) < 0) throw IOException("SET_CONTROL_LINE_STATE failed")
    }

    fun write(b: ByteArray) {
        if (conn.bulkTransfer(epOut, b, b.size, 1000) != b.size) throw IOException("USB write failed")
    }

    /** SET_CONTROL_LINE_STATE: DTR bit 0, RTS bit 1 (the ESP32's USB-Serial/JTAG maps them to its boot and reset logic). */
    fun setLines(dtr: Boolean, rts: Boolean) {
        val v = (if (dtr) 1 else 0) or (if (rts) 2 else 0)
        if (conn.controlTransfer(0x21, 0x22, v, comm.id, null, 0, 1000) < 0) throw IOException("SET_CONTROL_LINE_STATE failed")
    }

    /** One synchronous read (for request / response protocols); the bytes read, 0 on timeout. */
    fun read(b: ByteArray, timeoutMs: Int): Int = maxOf(0, conn.bulkTransfer(epIn, b, b.size, timeoutMs))

    /**
     * Reads until [running] turns false, with [depth] requests of [size] bytes in flight so that the host never
     * leaves the endpoint idle; every completed transfer goes to [sink] (array, length).
     */
    fun readLoop(running: () -> Boolean, depth: Int = 8, size: Int = 16384, sink: (ByteArray, Int) -> Unit) {
        val reqs = List(depth) {
            UsbRequest().apply {
                if (!initialize(conn, epIn)) throw IOException("UsbRequest.initialize failed")
                val buf = ByteBuffer.allocate(size)
                clientData = buf
                if (!queue(buf)) throw IOException("UsbRequest.queue failed")
            }
        }
        try {
            while (running()) {
                val r = try {
                    conn.requestWait(200)
                } catch (_: java.util.concurrent.TimeoutException) {
                    continue
                } ?: throw IOException("USB read failed (device gone?)")
                val buf = r.clientData as ByteBuffer
                val n = buf.position()
                if (n > 0) sink(buf.array(), n)
                buf.clear()
                if (!r.queue(buf)) throw IOException("UsbRequest.queue failed")
            }
        } finally {
            reqs.forEach { it.cancel(); it.close() }
        }
    }

    fun close() {
        conn.releaseInterface(data)
        conn.releaseInterface(comm)
        conn.close()
    }
}
