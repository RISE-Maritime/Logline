package se.rise.logline.keelson

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.MulticastSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

private const val TAG = "Scout"

/** A router that answered a scan, in the shape the picker needs. */
data class DiscoveredRouter(val label: String, val locators: List<String>)

/**
 * Scan the local network for routers, without joining anything.
 *
 * **This speaks Zenoh's scouting exchange directly instead of calling `Zenoh.scout`, deliberately.**
 * The binding's scout callback is delivered from one of Zenoh's own tokio threads, and its native side
 * builds the `ZenohId` argument with `FindClass` — which on a natively-attached thread resolves against
 * the system class loader and cannot see app classes. The result is not an exception but
 * `JNI DETECTED ERROR IN APPLICATION … ClassNotFoundException: io.zenoh.jni.config.ZenohId` and an
 * immediate SIGABRT: **the whole app disappears the moment a router answers**, which is the worst
 * possible failure mode for a discovery feature. Verified on zenoh-kotlin 1.10.0 / Android 17; no
 * callback the app can write avoids it, because the id is constructed before any Kotlin runs.
 *
 * The exchange itself is three bytes out and one datagram back, and is pinned by [encodeScout] and
 * [decodeHello] against captures from a real Zenoh node.
 *
 * Requires `ACCESS_LOCAL_NETWORK`: Android 16 introduced Local Network Protections and Android 17
 * enforces them, so multicast without it fails as `EPERM` — indistinguishable from an empty network.
 * The caller asks for the permission; [isLocalEndpoint] is the same gate for a LAN *endpoint*.
 *
 * Multicast carries `ttl: 1` to a link-local group, so this only ever finds routers on this network
 * segment — never one across the internet.
 *
 * @param address the multicast socket to scout on; deployments move it, so it is configurable.
 */
suspend fun scoutRouters(
    context: Context,
    address: String,
    timeoutMillis: Long = 3_000L,
): List<DiscoveredRouter> = withContext(Dispatchers.IO) {
    val group = parseSocketAddress(address)
        ?: throw IllegalArgumentException("\"$address\" is not a host:port multicast address")

    val found = LinkedHashMap<String, DiscoveredRouter>()
    val manager = context.getSystemService(ConnectivityManager::class.java)
    val network = manager?.activeNetwork

    // A MulticastSocket only to get `setNetworkInterface`, which is API 1 where `DatagramSocket`'s
    // equivalent option needs API 33. No group is joined: replies come back unicast.
    MulticastSocket().use { socket ->
        // Steer the datagrams onto the network the phone is actually using. Android marks sockets with
        // a network id, and an unmarked socket's multicast has nowhere to go when both WiFi and mobile
        // data are up.
        network?.let { runCatching { it.bindSocket(socket) }.onFailure { e -> Log.w(TAG, "bindSocket", e) } }
        activeInterfaceName(context)?.let { name ->
            runCatching { NetworkInterface.getByName(name) }.getOrNull()?.let { iface ->
                runCatching { socket.networkInterface = iface }
                    .onFailure { Log.w(TAG, "could not send from $name", it) }
            }
        }
        socket.soTimeout = RECEIVE_POLL_MILLIS

        val request = encodeScout(WHAT_ROUTER)
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        // MIN_VALUE rather than 0, so the first pass sends without depending on nanoTime's origin.
        var nextSend = Long.MIN_VALUE
        val buffer = ByteArray(2048)

        while (System.nanoTime() < deadline) {
            // Resent while the window is open: multicast over WiFi is lossy — measured on this phone,
            // one of two identical datagrams reached a listener on the same LAN.
            if (System.nanoTime() >= nextSend) {
                socket.send(DatagramPacket(request, request.size, group))
                nextSend = System.nanoTime() + RESEND_MILLIS * 1_000_000L
            }
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (_: SocketTimeoutException) {
                continue
            }
            val hello = decodeHello(packet.data.copyOf(packet.length)) ?: continue
            if (hello.locators.isEmpty()) continue // Nothing to connect to, so nothing to offer.
            found[hello.zid] = DiscoveredRouter(
                label = "${hello.role} · ${hello.zid}",
                locators = hello.locators,
            )
        }
    }

    Log.i(TAG, "scan on $address found ${found.size} router(s)")
    found.values.toList()
}

/** The scouting bitfield: router `1`, peer `2`, client `4`. Only routers can be published to. */
private const val WHAT_ROUTER = 0x01

private const val SCOUT_ID = 0x01
private const val HELLO_ID = 0x02

/** Zenoh's protocol version, as sent by a 1.10 node. */
internal const val ZENOH_PROTOCOL_VERSION = 0x09

/** Header bit saying the Hello carries a locator list. */
private const val FLAG_LOCATORS = 0x20

private const val RECEIVE_POLL_MILLIS = 250
private const val RESEND_MILLIS = 700L

/**
 * A Scout request: message id, protocol version, and the kinds of node being looked for.
 *
 * Three bytes, byte-for-byte what a real Zenoh scout emits — captured from zenoh-python 1.10 rather
 * than derived from the spec, and pinned by `ScoutWireTest`.
 */
internal fun encodeScout(what: Int): ByteArray =
    byteArrayOf(SCOUT_ID.toByte(), ZENOH_PROTOCOL_VERSION.toByte(), what.toByte())

/** What a Hello carries, before it becomes something the picker can show. */
internal data class HelloMessage(val role: String, val zid: String, val locators: List<String>)

/**
 * Parse a Hello, or return null for anything that is not one.
 *
 * ```
 * 22                       header: id 0x02, flag 0x20 = locators follow
 * 09                       protocol version
 * f0                       (zid length - 1) << 4 | whatami   → 16-byte zid, whatami 0 = router
 * 25e7…0a                  the zid, that many bytes
 * 01                       locator count, a varint
 * 16 "tcp/192.168.0.156:7447"   each locator: varint length, then UTF-8
 * ```
 *
 * Anything trailing is ignored: the header's top bit marks protocol extensions this does not need.
 * Every length is checked before use — these bytes arrive from whatever else is on the network.
 */
internal fun decodeHello(bytes: ByteArray): HelloMessage? {
    if (bytes.size < 3) return null
    val header = bytes[0].toInt() and 0xFF
    if (header and 0x1F != HELLO_ID) return null

    val flags = bytes[2].toInt() and 0xFF
    val zidLength = ((flags ushr 4) and 0x0F) + 1
    if (bytes.size < 3 + zidLength) return null
    val zid = bytes.copyOfRange(3, 3 + zidLength)

    val locators = mutableListOf<String>()
    if (header and FLAG_LOCATORS != 0) {
        val reader = ByteReader(bytes, 3 + zidLength)
        val count = reader.varInt() ?: return null
        repeat(count.toInt()) {
            val length = reader.varInt() ?: return@repeat
            val text = reader.string(length.toInt()) ?: return@repeat
            locators += text
        }
    }
    return HelloMessage(role = roleOf(flags and 0x03), zid = zidString(zid), locators = locators)
}

/** In a Hello the node kind is an ordinal, unlike the bitfield a Scout sends. */
private fun roleOf(whatAmI: Int): String = when (whatAmI) {
    0 -> "Router"
    1 -> "Peer"
    2 -> "Client"
    else -> "Node"
}

/**
 * The standard string form of a Zenoh id: the bytes read little-endian as one integer, in lowercase
 * hex without leading zeros. Same rule as every other Zenoh SDK, so ids match what tooling prints.
 */
internal fun zidString(bytes: ByteArray): String {
    val hex = StringBuilder(bytes.size * 2)
    for (i in bytes.indices.reversed()) {
        hex.append("0123456789abcdef"[(bytes[i].toInt() and 0xFF) ushr 4])
        hex.append("0123456789abcdef"[bytes[i].toInt() and 0x0F])
    }
    return hex.toString().trimStart('0').ifEmpty { "0" }
}

/** Bounds-checked cursor: a malformed datagram must return null, never throw into the scan loop. */
private class ByteReader(private val bytes: ByteArray, private var index: Int) {

    /** Zenoh's varint: seven bits per byte, low group first, top bit meaning "more". */
    fun varInt(): Long? {
        var value = 0L
        var shift = 0
        while (shift <= 63) {
            if (index >= bytes.size) return null
            val byte = bytes[index++].toInt() and 0xFF
            value = value or ((byte and 0x7F).toLong() shl shift)
            if (byte and 0x80 == 0) return value
            shift += 7
        }
        return null
    }

    fun string(length: Int): String? {
        if (length < 0 || index + length > bytes.size) return null
        val text = String(bytes, index, length, Charsets.UTF_8)
        index += length
        return text
    }
}

/** Split `host:port`, tolerating the whitespace a text field collects. Null when it is neither. */
internal fun parseSocketAddress(address: String): InetSocketAddress? {
    val trimmed = address.trim()
    val separator = trimmed.lastIndexOf(':')
    if (separator <= 0) return null
    val port = trimmed.substring(separator + 1).toIntOrNull() ?: return null
    if (port !in 1..65535) return null
    return runCatching {
        InetSocketAddress(InetAddress.getByName(trimmed.substring(0, separator)), port)
    }.getOrNull()
}

/**
 * The interface name of the network the phone is actually using, e.g. `wlan0`.
 *
 * Asked of `ConnectivityManager` rather than by enumerating interfaces, because the question that
 * matters is which network is *active* — with WiFi and mobile data both up, a scan sent on the wrong
 * one goes nowhere.
 */
internal fun activeInterfaceName(context: Context): String? {
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
    val network = manager.activeNetwork ?: return null
    val link: LinkProperties = manager.getLinkProperties(network) ?: return null
    return link.interfaceName
}
