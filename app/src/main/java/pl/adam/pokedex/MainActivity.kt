package pl.adam.pokedex

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.*
import java.io.ByteArrayOutputStream
import java.util.UUID

@SuppressLint("MissingPermission", "SetTextI18n")
class MainActivity : Activity() {

    data class Mon(val id: Int, val name: String, val type: String, val rarity: Int,
                   val rssi: Int, val seen: Int, val ssid: String)

    private val UART = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    private val RX   = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
    private val TX   = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
    private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val RARITY = arrayOf("Zwykła", "Rzadka", "Epicka", "LEGENDARNA")
    private val COLOR  = intArrayOf(0xFF9AA0A6.toInt(), 0xFF4FC3F7.toInt(), 0xFFC77DFF.toInt(), 0xFFFFD60A.toInt())

    private val ui = Handler(Looper.getMainLooper())
    private val dex = HashMap<Int, Mon>()
    private val buf = ByteArrayOutputStream()
    private var gatt: BluetoothGatt? = null
    private var scanning = false

    private lateinit var status: TextView
    private lateinit var stats: TextView
    private lateinit var list: LinearLayout

    // ---------------- UI ----------------
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF111111.toInt())
            setPadding(dp(14), dp(18), dp(14), 0)
        }
        root.addView(text("◉ POKEDEX SIECI", 24f, 0xFFFF3B3B.toInt(), true))
        status = text("Startuję…", 13f, 0xFF888888.toInt())
        stats = text("", 13f, Color.WHITE)
        root.addView(status); root.addView(stats)
        root.addView(Button(this).apply {
            text = "Połącz / odśwież"
            setOnClickListener { refresh() }
        })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(list) },
            LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        askPermissions()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color)
        typeface = Typeface.create(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun render() {
        val cnt = IntArray(4)
        dex.values.forEach { cnt[it.rarity]++ }
        stats.text = "Złapano: ${dex.size}   ★${cnt[3]}  ◆${cnt[2]}  ●${cnt[1]}  ·${cnt[0]}"
        list.removeAllViews()
        dex.values.sortedWith(compareBy({ -it.rarity }, { it.id })).forEach { m ->
            val c = COLOR[m.rarity]
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = GradientDrawable().apply {
                    setColor(0xFF1B1B1B.toInt()); cornerRadius = dp(6).toFloat(); setStroke(dp(2), c)
                }
            }
            card.addView(text("#%03d %s".format(m.id, m.name), 18f, c, true))
            card.addView(text("${m.type} · ${RARITY[m.rarity]}", 14f, Color.WHITE))
            card.addView(text("\"${m.ssid}\" · ${m.rssi} dBm · ${m.seen}x", 11f, 0xFF888888.toInt()))
            list.addView(card, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(5), 0, dp(5)) })
        }
    }

    private fun setStatus(t: String) = ui.post { status.text = t }

    // ---------------- uprawnienia ----------------
    private fun perms() = if (Build.VERSION.SDK_INT >= 31)
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun askPermissions() {
        val missing = perms().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startScan() else requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        if (r.isNotEmpty() && r.all { it == PackageManager.PERMISSION_GRANTED }) startScan()
        else setStatus("Bez uprawnień Bluetooth nie da rady :(")
    }

    // ---------------- Bluetooth ----------------
    private fun adapter() = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private fun refresh() {
        val g = gatt
        if (g != null) sendList(g) else askPermissions()
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(type: Int, r: ScanResult) {
            val name = r.scanRecord?.deviceName ?: r.device.name
            if (name == "Pokedex" && scanning) {
                stopScan()
                setStatus("Znalazłem Pokedex, łączę…")
                gatt = r.device.connectGatt(this@MainActivity, false, gattCb, BluetoothDevice.TRANSPORT_LE)
            }
        }
    }

    private fun startScan() {
        val a = adapter()
        if (a == null || !a.isEnabled) { setStatus("Włącz Bluetooth i kliknij Połącz"); return }
        if (scanning || gatt != null) return
        scanning = true
        setStatus("Szukam Pokedexu…")
        a.bluetoothLeScanner?.startScan(scanCb)
        ui.postDelayed({ if (scanning) { stopScan(); setStatus("Nie znalazłem. ESP włączone? Kliknij Połącz") } }, 15000)
    }

    private fun stopScan() {
        scanning = false
        try { adapter()?.bluetoothLeScanner?.stopScan(scanCb) } catch (_: Exception) {}
    }

    @Suppress("DEPRECATION")
    private fun sendList(g: BluetoothGatt) {
        val rx = g.getService(UART)?.getCharacteristic(RX) ?: return
        rx.value = "LIST\n".toByteArray()
        rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        g.writeCharacteristic(rx)
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, state: Int) {
            if (state == BluetoothProfile.STATE_CONNECTED) {
                setStatus("Połączono ✓")
                g.requestMtu(247)
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                g.close(); gatt = null
                setStatus("Rozłączono — ponawiam za chwilę…")
                ui.postDelayed({ startScan() }, 3000)
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, st: Int) { g.discoverServices() }

        @Suppress("DEPRECATION")
        override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
            val tx = g.getService(UART)?.getCharacteristic(TX)
            if (tx == null) { setStatus("To nie jest Pokedex?"); return }
            g.setCharacteristicNotification(tx, true)
            val d = tx.getDescriptor(CCCD)
            d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            g.writeDescriptor(d)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, st: Int) {
            setStatus("Połączono ✓ pobieram kolekcję…")
            sendList(g)
        }

        @Deprecated("stare API, działa na wszystkich Androidach")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") val v = c.value ?: return
            val lines = ArrayList<String>()
            synchronized(buf) {
                for (b in v) {
                    if (b == '\n'.code.toByte()) { lines.add(String(buf.toByteArray(), Charsets.UTF_8)); buf.reset() }
                    else buf.write(b.toInt())
                }
            }
            if (lines.isNotEmpty()) ui.post { lines.forEach { handle(it) } }
        }
    }

    // ---------------- protokół ----------------
    private fun handle(line: String) {
        val p = line.split('\t')
        when (p[0]) {
            "B" -> { dex.clear(); status.text = "Pobieram kolekcję…" }
            "D" -> { status.text = "Połączono ✓"; render() }
            "E", "N" -> if (p.size >= 8) {
                val m = Mon(p[1].toInt(), p[2], p[3], p[4].toInt().coerceIn(0, 3),
                            p[5].toInt(), p[6].toInt(), p.subList(7, p.size).joinToString(" "))
                dex[m.id] = m
                if (p[0] == "N") {
                    Toast.makeText(this, "ZŁAPANO! #%03d %s (%s)".format(m.id, m.name, RARITY[m.rarity]),
                        Toast.LENGTH_LONG).apply { setGravity(Gravity.TOP, 0, dp(80)) }.show()
                    render()
                }
            }
        }
    }

    override fun onDestroy() {
        stopScan(); gatt?.close(); super.onDestroy()
    }
}
