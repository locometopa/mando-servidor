package com.locometopa.mandoservidor

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.core.content.FileProvider
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import com.jcraft.jsch.Session
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.*
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.concurrent.thread

class MainActivity : Activity() {

    // ══════════════════════════════════════════════════════════════
    //  Paleta
    // ══════════════════════════════════════════════════════════════

    companion object {
        val FONDO       = Color.rgb(24, 25, 30)
        val TARJETA     = Color.rgb(38, 40, 48)
        val BORDE       = Color.rgb(55, 58, 68)
        val ACENTO      = Color.rgb(99, 140, 255)   // azul claro para detalles
        val VERDE       = Color.rgb(56, 142, 60)
        val NARANJA     = Color.rgb(239, 108, 0)
        val ROJO        = Color.rgb(211, 47, 47)
        val AZUL        = Color.rgb(55, 71, 86)
        val GRIS_BTN    = Color.rgb(60, 63, 74)
        val GRIS        = Color.rgb(158, 162, 176)
        val TEXTO       = Color.rgb(230, 232, 240)
        val TEXTO_SEC   = Color.rgb(140, 144, 158)
        val VERDE_CL    = Color.rgb(129, 199, 132)
        val ROJO_CL     = Color.rgb(239, 115, 115)
        val AMARILLO    = Color.rgb(255, 213, 79)
    }

    // ══════════════════════════════════════════════════════════════
    //  Modelo
    // ══════════════════════════════════════════════════════════════

    data class Equipo(
        val id: String, val nombre: String, val so: String, val mac: String,
        val lan: String, val ts: String, val usuario: String, val web: String,
    )

    class Resultado(val ok: Boolean, val texto: String)

    data class HostEncontrado(val ip: String, val hostname: String, val puertos: List<Int>) {
        fun soAdivinado(): String = when {
            22 in puertos && 445 !in puertos && 3389 !in puertos -> "linux"
            3389 in puertos || 445 in puertos -> "windows"
            else -> "wol"
        }
        fun etiqueta(): String {
            val so = when (soAdivinado()) { "linux" -> "Linux"; "windows" -> "Windows"; else -> "Otro" }
            val h = if (hostname.isNotEmpty() && hostname != ip) hostname else ""
            return "$ip  ·  $so" + if (h.isNotEmpty()) "  ·  $h" else ""
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  Estado
    // ══════════════════════════════════════════════════════════════

    private lateinit var prefs: SharedPreferences
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var contenido: LinearLayout
    private lateinit var aviso: TextView

    @Volatile private var equipos: List<Equipo> = emptyList()
    private val vivos = ConcurrentHashMap<String, String>()
    private val estados = HashMap<String, TextView>()
    private val statsTv = HashMap<String, TextView>()
    private val stats = ConcurrentHashMap<String, String>()
    private val ultimoStats = ConcurrentHashMap<String, Long>()
    @Volatile private var generacion = 0

    private val nasCampos = linkedMapOf(
        "nasUsuario"   to ("Usuario del NAS" to "locometopa"),
        "nasLan"       to ("IP del NAS (casa)" to "192.168.1.150"),
        "nasTs"        to ("IP del NAS (Tailscale)" to "100.127.53.156"),
        "nasInterfaz"  to ("Interfaz de red del NAS" to "eth0"),
    )

    private fun nas(k: String): String =
        prefs.getString(k, null)?.trim()?.takeIf { it.isNotEmpty() } ?: nasCampos[k]!!.second

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ══════════════════════════════════════════════════════════════
    //  Datos
    // ══════════════════════════════════════════════════════════════

    private fun cargar() {
        val txt = prefs.getString("equipos", null)
        if (txt == null) {
            val s = Equipo(
                id = "servidor", nombre = "Servidor", so = "linux",
                mac = prefs.getString("mac", "") ?: "",
                lan = prefs.getString("servidorLan", null)?.takeIf { it.isNotBlank() } ?: "192.168.1.180",
                ts = prefs.getString("servidorTs", null)?.takeIf { it.isNotBlank() } ?: "100.124.132.5",
                usuario = prefs.getString("usuario", null)?.takeIf { it.isNotBlank() } ?: "locometopa",
                web = "http://192.168.1.180/",
            )
            prefs.getString("pwServidor", null)?.let { prefs.edit().putString("pw_servidor", it).apply() }
            equipos = listOf(s)
            guardar()
            return
        }
        val arr = JSONArray(txt)
        equipos = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Equipo(
                o.optString("id"), o.optString("nombre"), o.optString("so", "wol"), o.optString("mac"),
                o.optString("lan"), o.optString("ts"), o.optString("usuario"), o.optString("web"),
            )
        }
    }

    private fun guardar() {
        val arr = JSONArray()
        for (e in equipos) {
            arr.put(JSONObject().apply {
                put("id", e.id); put("nombre", e.nombre); put("so", e.so); put("mac", e.mac)
                put("lan", e.lan); put("ts", e.ts); put("usuario", e.usuario); put("web", e.web)
            })
        }
        prefs.edit().putString("equipos", arr.toString()).apply()
    }

    // ══════════════════════════════════════════════════════════════
    //  Interfaz principal
    // ══════════════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("mando", MODE_PRIVATE)
        window.statusBarColor = FONDO
        window.navigationBarColor = FONDO
        cargar()

        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(FONDO)
            setPadding(dp(18), dp(28), dp(18), dp(32))
        }

        // — Cabecera —
        raiz.addView(TextView(this).apply {
            text = "⚡  Mando servidor"
            setTextColor(Color.WHITE)
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(2), 0, 0, dp(2))
        })
        raiz.addView(TextView(this).apply {
            text = "Controla tus equipos de casa"
            setTextColor(TEXTO_SEC)
            textSize = 14f
            setPadding(dp(2), 0, 0, dp(12))
        })

        // — Aviso —
        aviso = TextView(this).apply {
            setTextColor(AMARILLO)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(6))
            visibility = View.GONE
        }
        raiz.addView(aviso)

        // — Lista de equipos —
        contenido = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        raiz.addView(contenido)

        // — Botones inferiores —
        raiz.addView(separador())
        raiz.addView(botonSecundario("＋   Añadir equipo") { editarEquipo(null) })
        raiz.addView(botonSecundario("🔍   Buscar equipos en la red") { buscarEnRed() })
        raiz.addView(botonSecundario("⚙   Ajustes del NAS") { ajustesNas() })
        raiz.addView(botonSecundario("↻   Buscar actualización  ·  v1.${versionActual()}") { buscarActualizacion(true) })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(FONDO)
            addView(raiz)
        })
        pintar()
        buscarActualizacion(false)
    }

    private fun pintar() {
        contenido.removeAllViews()
        estados.clear()
        statsTv.clear()
        if (equipos.isEmpty()) {
            contenido.addView(TextView(this).apply {
                text = "No hay equipos configurados.\nPulsa «Añadir equipo» o «Buscar equipos en la red»."
                setTextColor(TEXTO_SEC)
                textSize = 15f
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(32), dp(16), dp(32))
            })
        } else {
            for (e in equipos) contenido.addView(tarjeta(e))
            for (e in equipos) mostrarEstado(e)
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  Tarjeta de equipo
    // ══════════════════════════════════════════════════════════════

    private fun tarjeta(e: Equipo): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(TARJETA)
                setStroke(1, BORDE)
                cornerRadius = dp(16).toFloat()
            }
            setPadding(dp(18), dp(16), dp(18), dp(16))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
        }

        // — Cabecera: icono + nombre + editar —
        val cab = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val icono = when (e.so) { "linux" -> "🐧"; "windows" -> "🖥"; else -> "💡" }
        cab.addView(TextView(this).apply {
            text = "$icono  ${e.nombre}"
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, -2, 1f))
        cab.addView(TextView(this).apply {
            text = "✏️"
            textSize = 18f
            setPadding(dp(12), dp(4), dp(4), dp(4))
            setOnClickListener { editarEquipo(e) }
        })
        card.addView(cab)

        // — IP —
        card.addView(TextView(this).apply {
            text = e.lan + if (e.ts.isNotBlank()) "  ·  ${e.ts}" else ""
            setTextColor(TEXTO_SEC)
            textSize = 12f
            setPadding(0, dp(2), 0, dp(6))
        })

        // — Estado —
        val est = TextView(this).apply {
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(2), 0, 0)
        }
        estados[e.id] = est
        card.addView(est)

        // — Stats (solo Linux) —
        if (e.so == "linux") {
            val st = TextView(this).apply {
                textSize = 12f
                setTextColor(TEXTO_SEC)
                setPadding(0, dp(2), 0, 0)
                text = stats[e.id] ?: ""
            }
            statsTv[e.id] = st
            card.addView(st)
        }

        // — Botones de acción —
        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }
        fun btn(t: String, c: Int, a: () -> Unit) {
            fila.addView(Button(this).apply {
                text = t; isAllCaps = false; textSize = 14f; setTextColor(Color.WHITE)
                background = GradientDrawable().apply { setColor(c); cornerRadius = dp(10).toFloat() }
                setPadding(dp(4), 0, dp(4), 0)
                setOnClickListener { a() }
            }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(3); marginEnd = dp(3) })
        }
        btn("Encender", VERDE) { encender(e) }
        if (e.so != "wol") {
            btn("Reiniciar", NARANJA) {
                confirmar("¿Reiniciar ${e.nombre}?", "Se cortará lo que esté haciendo.") { apagar(e, true) }
            }
            btn("Apagar", ROJO) {
                val extra = if (e.so == "linux") "\n\nSi estás fuera de casa y es el servidor, perderás el acceso hasta que lo enciendas." else ""
                confirmar("¿Apagar ${e.nombre}?", "Se apagará ahora mismo.$extra") { apagar(e, false) }
            }
        }
        card.addView(fila)

        // — Botones secundarios —
        if (e.so == "linux") {
            card.addView(botonTarjeta("🐳  Contenedores Docker") { contenedores(e) })
        }
        if (e.web.isNotBlank()) {
            card.addView(botonTarjeta("🌐  Abrir web") {
                val h = vivos[e.id]
                abrir(if (!h.isNullOrEmpty() && h != e.lan && e.lan.isNotBlank()) e.web.replace(e.lan, h) else e.web)
            })
        }
        return card
    }

    private fun mostrarEstado(e: Equipo) {
        val tv = estados[e.id] ?: return
        val h = vivos[e.id]
        when {
            h == null  -> { tv.text = "●  Comprobando…"; tv.setTextColor(AMARILLO) }
            h.isEmpty() -> { tv.text = "●  Apagado o sin conexión"; tv.setTextColor(ROJO_CL) }
            else -> {
                tv.text = "●  Encendido  ·  " + (if (h == e.lan) "en casa" else "Tailscale")
                tv.setTextColor(VERDE_CL)
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  Componentes reutilizables
    // ══════════════════════════════════════════════════════════════

    private fun botonSecundario(t: String, accion: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 15f; setTextColor(TEXTO); gravity = Gravity.START or Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply { setColor(GRIS_BTN); cornerRadius = dp(12).toFloat() }
        setPadding(dp(18), 0, dp(18), 0)
        layoutParams = LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(8) }
        setOnClickListener { accion() }
    }

    private fun botonTarjeta(t: String, accion: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 14f; setTextColor(ACENTO)
        setBackgroundColor(Color.TRANSPARENT); gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setPadding(0, dp(2), 0, 0)
        layoutParams = LinearLayout.LayoutParams(-1, dp(40)).apply { topMargin = dp(4) }
        setOnClickListener { accion() }
    }

    private fun separador() = View(this).apply {
        setBackgroundColor(BORDE)
        layoutParams = LinearLayout.LayoutParams(-1, 1).apply {
            topMargin = dp(20); bottomMargin = dp(8)
        }
    }

    private fun avisar(t: String) {
        ui.post {
            aviso.text = t
            aviso.visibility = if (t.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun mensaje(titulo: String, texto: String) {
        AlertDialog.Builder(this).setTitle(titulo).setMessage(texto).setPositiveButton("Vale", null).show()
    }

    private fun confirmar(titulo: String, texto: String, accion: () -> Unit) {
        AlertDialog.Builder(this).setTitle(titulo).setMessage(texto)
            .setPositiveButton("Sí") { _, _ -> accion() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun abrir(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(if (url.contains("://")) url else "http://$url"))) }
        catch (e: Exception) { avisar("No se pudo abrir el navegador.") }
    }

    // ══════════════════════════════════════════════════════════════
    //  Búsqueda de equipos en la red local
    // ══════════════════════════════════════════════════════════════

    private fun buscarEnRed() {
        avisar("Buscando equipos en la red…")
        thread {
            val encontrados = mutableListOf<HostEncontrado>()
            // Detectar mi subred
            val prefijo = try {
                val ifaces = NetworkInterface.getNetworkInterfaces().toList()
                val addr = ifaces.flatMap { it.inetAddresses.toList() }
                    .filterIsInstance<Inet4Address>()
                    .firstOrNull { !it.isLoopbackAddress && it.hostAddress?.startsWith("192.168.") == true }
                addr?.hostAddress?.substringBeforeLast('.') ?: "192.168.1"
            } catch (e: Exception) {
                "192.168.1"
            }
            ui.post { aviso.text = "Escaneando $prefijo.1-254…" }

            // Escanear en paralelo
            val hilos = (1..254).map { n ->
                thread {
                    val ip = "$prefijo.$n"
                    val puertosAbiertos = mutableListOf<Int>()
                    val checks = listOf(22, 80, 443, 445, 3389, 5000, 8080, 9090).map { p ->
                        thread {
                            if (puerto(ip, p, 400)) synchronized(puertosAbiertos) { puertosAbiertos += p }
                        }
                    }
                    checks.forEach { it.join() }
                    if (puertosAbiertos.isNotEmpty()) {
                        // Intentar resolver hostname
                        val hostname = try { InetAddress.getByName(ip).canonicalHostName } catch (e: Exception) { ip }
                        synchronized(encontrados) { encontrados += HostEncontrado(ip, if (hostname != ip) hostname else "", puertosAbiertos.sorted()) }
                    }
                }
            }
            hilos.forEach { it.join() }
            encontrados.sortBy { it.ip.split('.').last().toIntOrNull() ?: 999 }

            // Filtrar los ya añadidos
            val ips = equipos.map { it.lan }.toSet()
            val nuevos = encontrados.filter { it.ip !in ips }

            ui.post {
                avisar("")
                if (isFinishing) return@post
                if (encontrados.isEmpty()) {
                    mensaje("Sin resultados", "No se encontró ningún equipo en la red $prefijo.x")
                    return@post
                }
                val textos = encontrados.map { h ->
                    (if (h.ip in ips) "✔  " else "    ") + h.etiqueta()
                }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("Equipos en $prefijo.x  (${encontrados.size})")
                    .setItems(textos) { _, i ->
                        val h = encontrados[i]
                        if (h.ip in ips) {
                            mensaje("Ya añadido", "${h.ip} ya está en tu lista de equipos.")
                        } else {
                            editarEquipoDesdeRed(h)
                        }
                    }
                    .setNegativeButton("Cerrar", null)
                    .show()
            }
        }
    }

    private fun editarEquipoDesdeRed(h: HostEncontrado) {
        val so = h.soAdivinado()
        val nombre = when {
            h.hostname.isNotEmpty() -> h.hostname.substringBefore('.').replaceFirstChar { it.uppercase() }
            so == "linux" -> "Equipo Linux"
            so == "windows" -> "PC Windows"
            else -> "Equipo"
        }
        editarEquipo(null, Equipo(
            id = "", nombre = nombre, so = so, mac = "",
            lan = h.ip, ts = "", usuario = "", web = ""
        ))
    }

    // ══════════════════════════════════════════════════════════════
    //  Estado en segundo plano
    // ══════════════════════════════════════════════════════════════

    override fun onResume() {
        super.onResume()
        val gen = ++generacion
        thread {
            while (gen == generacion) {
                val hilos = equipos.map { e ->
                    thread {
                        val h = comprobar(e)
                        vivos[e.id] = h ?: ""
                        ui.post { mostrarEstado(e) }
                        if (h == null) {
                            stats.remove(e.id); ultimoStats.remove(e.id)
                            ui.post { statsTv[e.id]?.text = "" }
                        } else if (e.so == "linux" && System.currentTimeMillis() - (ultimoStats[e.id] ?: 0L) > 30000) {
                            ultimoStats[e.id] = System.currentTimeMillis()
                            val t = leerStats(e, h)
                            if (t != null) { stats[e.id] = t; ui.post { statsTv[e.id]?.text = t } }
                        }
                    }
                }
                hilos.forEach { it.join() }
                Thread.sleep(3000)
            }
        }
    }

    override fun onPause() { super.onPause(); generacion++ }

    private fun puerto(host: String, p: Int, ms: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, p), ms); true }
    } catch (e: Exception) { false }

    private fun responde(host: String, puertos: List<Int>, ms: Int): Boolean {
        if (host.isBlank()) return false
        val ok = AtomicBoolean(false)
        puertos.map { p -> thread { if (puerto(host, p, ms)) ok.set(true) } }.forEach { it.join() }
        return ok.get()
    }

    private fun comprobar(e: Equipo): String? {
        val puertos = if (e.so == "linux") listOf(22) else listOf(22, 3389, 445, 135)
        return when {
            responde(e.lan, puertos, 800) -> e.lan
            responde(e.ts, puertos, 2000) -> e.ts
            else -> null
        }
    }

    private fun hostDe(e: Equipo): String = vivos[e.id]?.takeIf { it.isNotEmpty() } ?: e.lan

    // ══════════════════════════════════════════════════════════════
    //  Stats del servidor (CPU, RAM, temp, discos)
    // ══════════════════════════════════════════════════════════════

    private val ordenStats = (
        "echo \"C §(cut -d' ' -f1 /proc/loadavg) §(nproc)\"; " +
            "free -m | awk '/^Mem/{print \"M\",§3,§2}'; " +
            "echo \"T §(cat /sys/class/hwmon/hwmon*/temp*_input 2>/dev/null | sort -n | tail -1)\"; " +
            "df -h / /datos 2>/dev/null | awk 'NR>1{print \"D\",§6,§5}'; " +
            "echo \"U §(cut -d. -f1 /proc/uptime)\""
        ).replace("§", "$")

    private fun leerStats(e: Equipo, host: String): String? {
        val r = ssh(e.usuario, host, ordenStats)
        if (!r.ok) return null
        val p1 = mutableListOf<String>()
        val p2 = mutableListOf<String>()
        for (l in r.texto.lines()) {
            val p = l.trim().split(Regex("\\s+"))
            when (p.getOrNull(0)) {
                "C" -> if (p.size >= 3) p1 += "⚡ ${p[1]} (${p[2]} hilos)"
                "M" -> {
                    val u = p.getOrNull(1)?.toDoubleOrNull(); val t = p.getOrNull(2)?.toDoubleOrNull()
                    if (u != null && t != null && t > 0) p1 += String.format("💾 %.1f/%.1f GB", u / 1024, t / 1024)
                }
                "T" -> p.getOrNull(1)?.toIntOrNull()?.let { if (it > 0) p1 += "🌡 ${it / 1000}°C" }
                "D" -> if (p.size >= 3) p2 += "📁 ${p[1]} ${p[2]}"
                "U" -> p.getOrNull(1)?.toLongOrNull()?.let { seg ->
                    val d = seg / 86400; val h = (seg % 86400) / 3600; val m = (seg % 3600) / 60
                    p2 += "⏱ " + when { d > 0 -> "${d}d ${h}h"; h > 0 -> "${h}h ${m}m"; else -> "${m}m" }
                }
            }
        }
        return p1.joinToString("  ") + if (p2.isNotEmpty()) "\n" + p2.joinToString("  ") else ""
    }

    // ══════════════════════════════════════════════════════════════
    //  Contenedores Docker
    // ══════════════════════════════════════════════════════════════

    private fun contenedores(e: Equipo) {
        avisar("${e.nombre}: leyendo contenedores…")
        thread {
            val r = ssh(e.usuario, hostDe(e), "docker ps -a --format '{{.Names}}|{{.Status}}'")
            if (!r.ok) {
                avisar("${e.nombre}: error: ${r.texto}" + pista(r.texto))
                return@thread
            }
            val lista = r.texto.lines().mapNotNull { l ->
                val p = l.split("|", limit = 2)
                val n = p[0].trim()
                if (p.size == 2 && Regex("^[A-Za-z0-9_.-]+$").matches(n)) n to p[1].trim() else null
            }.sortedBy { it.first }
            ui.post {
                avisar("")
                if (isFinishing) return@post
                val textos = lista.map { (n, st) ->
                    (if (st.startsWith("Up")) "🟢  " else "🔴  ") + n + "  ·  " + estadoDocker(st)
                }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("🐳  ${e.nombre}")
                    .setItems(textos) { _, i -> accionContenedor(e, lista[i].first, lista[i].second.startsWith("Up")) }
                    .setNegativeButton("Cerrar", null)
                    .show()
            }
        }
    }

    private fun estadoDocker(st: String) = when {
        st.startsWith("Up") -> "en marcha" + if ("unhealthy" in st) " ⚠" else ""
        st.startsWith("Exited") -> "parado"
        st.startsWith("Restarting") -> "reiniciándose"
        st.startsWith("Created") -> "creado"
        else -> st
    }

    private fun accionContenedor(e: Equipo, nombre: String, enMarcha: Boolean) {
        val acciones = if (enMarcha) arrayOf("Reiniciar", "Parar") else arrayOf("Arrancar")
        AlertDialog.Builder(this).setTitle(nombre)
            .setItems(acciones) { _, i ->
                val (cmd, txt) = when (acciones[i]) {
                    "Reiniciar" -> "restart" to "reiniciando"
                    "Parar" -> "stop" to "parando"
                    else -> "start" to "arrancando"
                }
                avisar("$nombre: $txt…")
                thread {
                    val r = ssh(e.usuario, hostDe(e), "docker $cmd $nombre")
                    avisar(if (r.ok) "$nombre: hecho ✔" else "$nombre: error: ${r.texto}")
                }
            }
            .setNegativeButton("Cancelar", null).show()
    }

    // ══════════════════════════════════════════════════════════════
    //  Actualizaciones
    // ══════════════════════════════════════════════════════════════

    private fun versionActual(): Int = try {
        (packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0").substringAfter(".").toIntOrNull() ?: 0
    } catch (e: Exception) { 0 }

    private fun buscarActualizacion(manual: Boolean) {
        if (manual) avisar("Buscando actualización…")
        thread {
            try {
                val c = URL("https://api.github.com/repos/locometopa/mando-servidor/releases/latest").openConnection() as HttpURLConnection
                c.setRequestProperty("User-Agent", "MandoServidor")
                c.setRequestProperty("Accept", "application/vnd.github+json")
                c.connectTimeout = 8000; c.readTimeout = 8000
                val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                val nueva = j.optString("tag_name").substringAfter(".").toIntOrNull() ?: 0
                val actual = versionActual()
                var url = ""
                val assets = j.optJSONArray("assets")
                if (assets != null) {
                    val buscado = "MandoServidor-1.$nueva.apk"
                    for (i in 0 until assets.length()) {
                        val a = assets.optJSONObject(i) ?: continue
                        if (a.optString("name") == buscado) { url = a.optString("browser_download_url"); break }
                    }
                    if (url.isEmpty()) {
                        for (i in assets.length() - 1 downTo 0) {
                            val a = assets.optJSONObject(i) ?: continue
                            if (a.optString("name").endsWith(".apk")) { url = a.optString("browser_download_url"); break }
                        }
                    }
                }
                if (nueva > actual && url.isNotEmpty()) {
                    val urlFinal = url
                    ui.post {
                        if (isFinishing) return@post; avisar("")
                        AlertDialog.Builder(this)
                            .setTitle("Nueva versión 1.$nueva")
                            .setMessage("Tienes la 1.$actual. ¿Descargar e instalar?\n\nNo se pierden los equipos ni las contraseñas.")
                            .setPositiveButton("Descargar") { _, _ -> descargarEInstalar(urlFinal, nueva) }
                            .setNegativeButton("Más tarde", null).show()
                    }
                } else if (manual) avisar("Ya tienes la última versión (1.$actual).")
            } catch (e: Exception) {
                if (manual) avisar("No se pudo comprobar: ${e.message}")
            }
        }
    }

    private fun descargarEInstalar(url: String, version: Int) {
        avisar("Descargando versión 1.$version…")
        thread {
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.setRequestProperty("User-Agent", "MandoServidor"); c.instanceFollowRedirects = true
                c.connectTimeout = 15000; c.readTimeout = 30000
                val apk = File(cacheDir, "MandoServidor-1.$version.apk")
                c.inputStream.use { inp -> apk.outputStream().use { out -> inp.copyTo(out) } }
                c.disconnect()
                ui.post {
                    if (isFinishing) return@post
                    try {
                        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apk)
                        startActivity(Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "application/vnd.android.package-archive")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                    } catch (e: Exception) { avisar("Error al instalar: ${e.message}") }
                }
            } catch (e: Exception) { ui.post { avisar("Error al descargar: ${e.message}") } }
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  Encender (Wake-on-LAN)
    // ══════════════════════════════════════════════════════════════

    private fun macBytes(mac: String): ByteArray? {
        val h = mac.replace(":", "").replace("-", "").trim()
        if (!Regex("^[0-9A-Fa-f]{12}$").matches(h)) return null
        return ByteArray(6) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private fun enviarWol(mac: ByteArray, lan: String) {
        val p = ByteArray(102)
        for (i in 0 until 6) p[i] = 0xFF.toByte()
        for (r in 1..16) System.arraycopy(mac, 0, p, r * 6, 6)
        val destinos = mutableListOf("255.255.255.255")
        val partes = lan.split(".")
        if (partes.size == 4) destinos += "${partes[0]}.${partes[1]}.${partes[2]}.255"
        DatagramSocket().use { s ->
            s.broadcast = true
            for (d in destinos) for (port in listOf(7, 9)) {
                try { s.send(DatagramPacket(p, p.size, InetAddress.getByName(d), port)) } catch (_: Exception) {}
            }
        }
    }

    private fun encender(e: Equipo) {
        val mac = macBytes(e.mac)
        if (mac == null) { mensaje("Falta la MAC", "Pon la MAC de «${e.nombre}» en Editar."); return }
        avisar("${e.nombre}: enviando señal de encendido…")
        thread {
            try { enviarWol(mac, e.lan) } catch (_: Exception) {}
            val enCasa = listOf(445, 5000, 5001).any { puerto(nas("nasLan"), it, 600) }
            if (enCasa) {
                avisar("${e.nombre}: señal enviada. Tarda 1-2 min en arrancar.")
                return@thread
            }
            avisar("${e.nombre}: fuera de casa, enviando por el NAS…")
            val m = e.mac.trim(); val i = nas("nasInterfaz")
            val r = ejecutarLinux(nas("nasUsuario"), nas("nasTs"),
                "if [ -x /usr/syno/sbin/synonet ]; then SUDO /usr/syno/sbin/synonet --wake $m $i; else SUDO /usr/syno/bin/synonet --wake $m $i; fi",
                "pwNas")
            avisar(if (r.ok) "${e.nombre}: señal enviada por el NAS. Tarda 1-2 min." else "NAS: ${r.texto}" + pista(r.texto))
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  Apagar / Reiniciar
    // ══════════════════════════════════════════════════════════════

    private fun apagar(e: Equipo, reiniciar: Boolean) {
        avisar("${e.nombre}: " + if (reiniciar) "reiniciando…" else "apagando…")
        thread {
            val h = hostDe(e)
            val r = if (e.so == "linux") {
                ejecutarLinux(e.usuario, h, if (reiniciar) "SUDO /usr/sbin/reboot" else "SUDO /usr/sbin/poweroff", "pw_${e.id}")
            } else {
                val cmd = if (reiniciar) "shutdown /r /t 0" else "shutdown /s /t 0"
                val pw = leerClave("pw_${e.id}")
                val r1 = ssh(e.usuario, h, cmd)
                if (!r1.ok && !cortado(r1.texto) && !pw.isNullOrEmpty()) ssh(e.usuario, h, cmd, pw) else r1
            }
            avisar(if (r.ok || cortado(r.texto)) "${e.nombre}: orden enviada ✔" else "${e.nombre}: ${r.texto}" + pista(r.texto))
        }
    }

    private fun cortado(t: String) = "closed" in t.lowercase() || "código -1" in t || "reset" in t.lowercase()
    private fun esAuth(t: String) = "Auth fail" in t || "Auth cancel" in t

    private fun pista(t: String): String = when {
        esAuth(t) -> "\n\nRevisa el usuario y la contraseña."
        "password" in t.lowercase() -> "\n\nGuarda la contraseña para que pueda usar sudo."
        "timeout" in t.lowercase() || "connect" in t.lowercase() -> "\n\n¿Está encendido? Si estás fuera, activa Tailscale."
        else -> ""
    }

    private fun ejecutarLinux(usuario: String, host: String, plantilla: String, pwClave: String): Resultado {
        val r = ssh(usuario, host, plantilla.replace("SUDO", "sudo -n"))
        if (r.ok || cortado(r.texto)) return r
        val pw = leerClave(pwClave)
        if (pw.isNullOrEmpty()) return r
        val conSudo = plantilla.replace("SUDO", "sudo -S -p ''")
        if (!esAuth(r.texto)) {
            val r2 = ssh(usuario, host, conSudo, null, pw + "\n")
            if (r2.ok || cortado(r2.texto) || !esAuth(r2.texto)) return r2
        }
        return ssh(usuario, host, conSudo, pw, pw + "\n")
    }

    // ══════════════════════════════════════════════════════════════
    //  SSH
    // ══════════════════════════════════════════════════════════════

    private fun archivoClave() = File(filesDir, "clave_ssh")

    private fun clavePublica(): String {
        val priv = archivoClave(); val pub = File(filesDir, "clave_ssh.pub")
        if (!priv.exists() || !pub.exists()) {
            val kp = KeyPair.genKeyPair(JSch(), KeyPair.RSA, 3072)
            priv.outputStream().use { kp.writePrivateKey(it) }
            pub.outputStream().use { kp.writePublicKey(it, "mando-servidor-movil") }
            kp.dispose()
        }
        return pub.readText().trim()
    }

    private fun sesion(usuario: String, host: String, clave: String?): Session {
        val j = JSch()
        if (clave == null) { clavePublica(); j.addIdentity(archivoClave().absolutePath) }
        val s = j.getSession(usuario, host, 22)
        s.setConfig("StrictHostKeyChecking", "no")
        if (clave != null) {
            s.setPassword(clave); s.setConfig("PreferredAuthentications", "password,keyboard-interactive")
        } else s.setConfig("PreferredAuthentications", "publickey")
        s.connect(8000)
        return s
    }

    private fun ssh(usuario: String, host: String, orden: String, clave: String? = null, entrada: String? = null): Resultado {
        if (usuario.isBlank()) return Resultado(false, "falta el usuario")
        return try {
            val s = sesion(usuario, host, clave)
            try {
                val c = s.openChannel("exec") as ChannelExec
                c.setCommand(orden)
                val salida = ByteArrayOutputStream()
                c.setOutputStream(salida); c.setErrStream(salida)
                val stdin = c.outputStream; c.connect(8000)
                if (entrada != null) { stdin.write(entrada.toByteArray()); stdin.flush() }
                stdin.close()
                var t = 0; while (!c.isClosed && t < 150) { Thread.sleep(100); t++ }
                val code = c.exitStatus; c.disconnect()
                Resultado(code == 0, salida.toString().trim().ifEmpty { "código $code" })
            } finally { s.disconnect() }
        } catch (e: Exception) { Resultado(false, e.message ?: e.javaClass.simpleName) }
    }

    private fun instalarLlave(pub: String, previo: String) =
        "${previo}mkdir -p ~/.ssh && chmod 700 ~/.ssh && touch ~/.ssh/authorized_keys && " +
            "(grep -qF '$pub' ~/.ssh/authorized_keys || echo '$pub' >> ~/.ssh/authorized_keys) && chmod 600 ~/.ssh/authorized_keys"

    private fun autorizar(nombre: String, usuario: String, host: String, pw: String, previo: String, sudoers: String) {
        thread {
            val pub = try { clavePublica() } catch (e: Exception) { avisar("$nombre: error llave: ${e.message}"); return@thread }
            avisar("$nombre: autorizando este móvil…")
            val r1 = ssh(usuario, host, instalarLlave(pub, previo), pw)
            val r2 = if (r1.ok) ssh(usuario, host, "sudo -S -p '' sh -c \"$sudoers\"", pw, pw + "\n") else r1
            avisar("$nombre: " + if (r1.ok && r2.ok) "móvil autorizado ✔" else "error: ${r2.texto}" + pista(r2.texto))
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  Contraseñas cifradas (Android Keystore)
    // ══════════════════════════════════════════════════════════════

    private fun claveAes(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry("mando_pw", null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder("mando_pw", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return g.generateKey()
    }

    private fun guardarClave(nombre: String, valor: String) {
        try {
            val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, claveAes())
            prefs.edit().putString(nombre, Base64.encodeToString(c.iv + c.doFinal(valor.toByteArray()), Base64.NO_WRAP)).apply()
        } catch (e: Exception) { avisar("No se pudo guardar la contraseña: ${e.message}") }
    }

    private fun leerClave(nombre: String): String? {
        val s = prefs.getString(nombre, null) ?: return null
        return try {
            val b = Base64.decode(s, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, claveAes(), GCMParameterSpec(128, b, 0, 12))
            String(c.doFinal(b, 12, b.size - 12))
        } catch (e: Exception) { null }
    }

    // ══════════════════════════════════════════════════════════════
    //  Diálogos de edición
    // ══════════════════════════════════════════════════════════════

    private fun etiqueta(caja: LinearLayout, t: String) {
        caja.addView(TextView(this).apply { text = t; textSize = 13f; setPadding(0, dp(10), 0, dp(2)) })
    }

    private fun campo(caja: LinearLayout, t: String, valor: String, pista: String = ""): EditText {
        etiqueta(caja, t)
        return EditText(this).apply {
            setText(valor); hint = pista
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine(true)
        }.also { caja.addView(it) }
    }

    private fun campoClave(caja: LinearLayout, t: String, guardada: Boolean): EditText {
        etiqueta(caja, t + if (guardada) "  ✔ guardada" else "")
        return EditText(this).apply {
            hint = if (guardada) "En blanco = no cambiarla" else "Contraseña"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }.also { caja.addView(it) }
    }

    private fun nuevaCaja() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8))
    }

    /** Editar o añadir equipo. Si [pre] no es null, se usa como plantilla (desde búsqueda de red). */
    private fun editarEquipo(orig: Equipo?, pre: Equipo? = null) {
        val base = orig ?: pre
        val caja = nuevaCaja()
        val fNombre = campo(caja, "Nombre", base?.nombre ?: "", "Ej. PC del salón")

        etiqueta(caja, "Tipo de equipo")
        val grupo = RadioGroup(this)
        val opciones = listOf("linux" to "🐧  Linux / servidor", "windows" to "🖥  Windows", "wol" to "💡  Solo encender")
        val ids = HashMap<Int, String>()
        for ((clave, texto) in opciones) {
            val rb = RadioButton(this).apply { text = texto; id = View.generateViewId() }
            ids[rb.id] = clave; grupo.addView(rb)
            if (clave == (base?.so ?: "windows")) grupo.check(rb.id)
        }
        caja.addView(grupo)

        val fMac = campo(caja, "MAC (tarjeta de red por cable)", base?.mac ?: "", "Ej. 80:ee:73:aa:bb:cc")
        val fLan = campo(caja, "IP en casa", base?.lan ?: "192.168.1.", "Ej. 192.168.1.159")
        val fTs  = campo(caja, "IP de Tailscale (opcional)", base?.ts ?: "", "Ej. 100.79.163.44")
        val fUsu = campo(caja, "Usuario SSH", base?.usuario ?: "", "Para apagar/reiniciar")
        val fPw  = campoClave(caja, "Contraseña", orig != null && leerClave("pw_${orig.id}") != null)
        val fWeb = campo(caja, "Dirección web (opcional)", base?.web ?: "", "Ej. http://192.168.1.180/")

        caja.addView(TextView(this).apply {
            textSize = 11f; setTextColor(GRIS)
            setPadding(0, dp(14), 0, 0)
            text = "• Linux: al guardar con contraseña, el móvil se autoriza solo.\n" +
                "• Windows: necesita «Servidor OpenSSH» activo.\n" +
                "• Para encender: cable + Wake-on-LAN activo en la BIOS."
        })

        val b = AlertDialog.Builder(this)
            .setTitle(if (orig == null) "Añadir equipo" else "Editar ${orig.nombre}")
            .setView(ScrollView(this).apply { addView(caja) })
            .setPositiveButton("Guardar", null)
            .setNegativeButton("Cancelar", null)
        if (orig != null) b.setNeutralButton("Borrar", null)
        val d = b.create()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val nombre = fNombre.text.toString().trim()
                val mac = fMac.text.toString().trim()
                val so = ids[grupo.checkedRadioButtonId] ?: "wol"
                when {
                    nombre.isEmpty() -> mensaje("Falta el nombre", "Ponle un nombre al equipo.")
                    mac.isNotEmpty() && macBytes(mac) == null -> mensaje("MAC incorrecta", "Ejemplo: 80:ee:73:aa:bb:cc")
                    else -> {
                        val e = Equipo(
                            id = orig?.id ?: ("e" + System.currentTimeMillis()), nombre = nombre, so = so, mac = mac,
                            lan = fLan.text.toString().trim().removeSuffix("."),
                            ts = fTs.text.toString().trim(), usuario = fUsu.text.toString().trim(),
                            web = fWeb.text.toString().trim(),
                        )
                        equipos = if (orig == null) equipos + e else equipos.map { if (it.id == e.id) e else it }
                        guardar()
                        val pw = fPw.text.toString()
                        if (pw.isNotEmpty()) {
                            guardarClave("pw_${e.id}", pw)
                            if (so == "linux" && e.usuario.isNotEmpty()) {
                                autorizar(e.nombre, e.usuario, hostDe(e), pw, "",
                                    "echo '${e.usuario} ALL=(root) NOPASSWD: /usr/sbin/poweroff, /usr/sbin/reboot' > /etc/sudoers.d/mando && chmod 440 /etc/sudoers.d/mando")
                            }
                        }
                        vivos.remove(e.id); pintar(); d.dismiss()
                    }
                }
            }
            if (orig != null) {
                d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    confirmar("¿Borrar ${orig.nombre}?", "Solo se quita de la app.") {
                        equipos = equipos.filter { it.id != orig.id }; guardar()
                        prefs.edit().remove("pw_${orig.id}").apply()
                        vivos.remove(orig.id); pintar(); d.dismiss()
                    }
                }
            }
        }
        d.show()
    }

    private fun ajustesNas() {
        val caja = nuevaCaja()
        caja.addView(TextView(this).apply {
            textSize = 13f
            text = "El NAS envía la señal de encendido cuando estás fuera de casa.\nNecesita Tailscale activo en el móvil y SSH en el NAS."
        })
        val cajas = LinkedHashMap<String, EditText>()
        for ((k, v) in nasCampos) cajas[k] = campo(caja, v.first, nas(k))
        val fPw = campoClave(caja, "Contraseña del NAS", leerClave("pwNas") != null)
        AlertDialog.Builder(this)
            .setTitle("⚙  Ajustes del NAS")
            .setView(ScrollView(this).apply { addView(caja) })
            .setPositiveButton("Guardar") { _, _ ->
                val ed = prefs.edit()
                for ((k, e) in cajas) ed.putString(k, e.text.toString().trim())
                ed.apply()
                val pw = fPw.text.toString()
                if (pw.isNotEmpty()) {
                    guardarClave("pwNas", pw)
                    thread {
                        val h = if (listOf(445, 5000, 5001).any { puerto(nas("nasLan"), it, 600) }) nas("nasLan") else nas("nasTs")
                        autorizar("NAS", nas("nasUsuario"), h, pw, "chmod 755 ~ && ",
                            "echo '${nas("nasUsuario")} ALL=(root) NOPASSWD: /usr/syno/sbin/synonet, /usr/syno/bin/synonet' > /etc/sudoers.d/despertar && chmod 440 /etc/sudoers.d/despertar")
                    }
                } else avisar("Ajustes guardados ✔")
            }
            .setNegativeButton("Cancelar", null).show()
    }
}
