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
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import com.jcraft.jsch.Session
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.URL
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.concurrent.thread

class MainActivity : Activity() {

    companion object {
        val FONDO = Color.rgb(30, 32, 36)
        val TARJETA = Color.rgb(44, 47, 53)
        val VERDE = Color.rgb(46, 125, 50)
        val NARANJA = Color.rgb(230, 126, 34)
        val ROJO = Color.rgb(198, 40, 40)
        val AZUL = Color.rgb(55, 71, 79)
        val GRIS_OSC = Color.rgb(69, 90, 100)
        val GRIS = Color.rgb(170, 170, 170)
        val AMARILLO = Color.rgb(255, 213, 79)
        val VERDE_CLARO = Color.rgb(129, 199, 132)
        val ROJO_CLARO = Color.rgb(239, 115, 115)
    }

    /** so: "linux" (apagar por SSH con sudo), "windows" (apagar con shutdown), "wol" (solo encender) */
    data class Equipo(
        val id: String,
        val nombre: String,
        val so: String,
        val mac: String,
        val lan: String,
        val ts: String,
        val usuario: String,
        val web: String,
    )

    class Resultado(val ok: Boolean, val texto: String)

    private lateinit var prefs: SharedPreferences
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var lista: LinearLayout
    private lateinit var aviso: TextView

    @Volatile private var equipos: List<Equipo> = emptyList()
    private val vivos = ConcurrentHashMap<String, String>()   // id -> host que responde ("" = apagado)
    private val estados = HashMap<String, TextView>()
    private val statsTv = HashMap<String, TextView>()
    private val stats = ConcurrentHashMap<String, String>()
    private val ultimoStats = ConcurrentHashMap<String, Long>()
    @Volatile private var generacion = 0

    private val nasCampos = linkedMapOf(
        "nasUsuario" to ("Usuario del NAS" to "locometopa"),
        "nasLan" to ("IP del NAS (casa)" to "192.168.1.150"),
        "nasTs" to ("IP del NAS (Tailscale)" to "100.127.53.156"),
        "nasInterfaz" to ("Interfaz de red del NAS" to "eth0"),
    )

    private fun nas(k: String): String =
        prefs.getString(k, null)?.trim()?.takeIf { it.isNotEmpty() } ?: nasCampos[k]!!.second

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---------------- Datos ----------------

    private fun cargar() {
        val txt = prefs.getString("equipos", null)
        if (txt == null) {
            // Primera vez (o versión anterior): el servidor de casa
            val s = Equipo(
                id = "servidor",
                nombre = "Servidor",
                so = "linux",
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

    // ---------------- Interfaz ----------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("mando", MODE_PRIVATE)
        window.statusBarColor = FONDO
        window.navigationBarColor = FONDO
        cargar()

        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(FONDO)
            setPadding(dp(16), dp(24), dp(16), dp(28))
        }
        raiz.addView(TextView(this).apply {
            text = "Equipos de casa"
            setTextColor(Color.WHITE)
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        })
        aviso = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        }
        raiz.addView(aviso)
        lista = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        raiz.addView(lista)
        raiz.addView(boton("+  Añadir equipo", GRIS_OSC) { editarEquipo(null) })
        raiz.addView(boton("Ajustes del NAS (encender desde fuera)", GRIS_OSC) { ajustesNas() })
        raiz.addView(boton("Buscar actualización (versión 1.${versionActual()})", GRIS_OSC, 46) { buscarActualizacion(true) })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(FONDO)
            addView(raiz)
        })
        pintar()
        buscarActualizacion(false)
    }

    private fun pintar() {
        lista.removeAllViews()
        estados.clear()
        statsTv.clear()
        for (e in equipos) lista.addView(tarjeta(e))
        for (e in equipos) mostrarEstado(e)
    }

    private fun tarjeta(e: Equipo): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(TARJETA); cornerRadius = dp(14).toFloat() }
            setPadding(dp(16), dp(12), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(14) }
        }
        val cab = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        cab.addView(TextView(this).apply {
            text = e.nombre
            setTextColor(Color.WHITE)
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        cab.addView(Button(this).apply {
            text = "Editar"
            isAllCaps = false
            textSize = 14f
            setTextColor(GRIS)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { editarEquipo(e) }
        })
        card.addView(cab)

        val est = TextView(this).apply {
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
        }
        estados[e.id] = est
        card.addView(est)
        if (e.so == "linux") {
            val st = TextView(this).apply {
                textSize = 13f
                setTextColor(GRIS)
                setPadding(0, dp(4), 0, 0)
                text = stats[e.id] ?: ""
            }
            statsTv[e.id] = st
            card.addView(st)
        }

        val fila = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun peq(t: String, c: Int, a: () -> Unit) {
            fila.addView(boton(t, c, 46, a), LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                topMargin = dp(10); marginStart = dp(3); marginEnd = dp(3)
            })
        }
        peq("Encender", VERDE) { encender(e) }
        if (e.so != "wol") {
            peq("Reiniciar", NARANJA) {
                confirmar("¿Reiniciar ${e.nombre}?", "Se cortará lo que esté haciendo.") { apagar(e, true) }
            }
            peq("Apagar", ROJO) {
                val extra = if (e.so == "linux") "\n\nSi estás fuera de casa y es el servidor, perderás el acceso a la red de casa hasta que lo enciendas." else ""
                confirmar("¿Apagar ${e.nombre}?", "Se apagará ahora mismo.$extra") { apagar(e, false) }
            }
        }
        card.addView(fila)
        if (e.so == "linux") {
            card.addView(boton("Contenedores Docker", AZUL, 44) { contenedores(e) })
        }
        if (e.web.isNotBlank()) {
            card.addView(boton("Abrir web", AZUL, 44) {
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
            h == null -> { tv.text = "●  Comprobando…"; tv.setTextColor(AMARILLO) }
            h.isEmpty() -> { tv.text = "●  Apagado o sin conexión"; tv.setTextColor(ROJO_CLARO) }
            else -> {
                tv.text = "●  Encendido · " + (if (h == e.lan) "en casa" else "por Tailscale")
                tv.setTextColor(VERDE_CLARO)
            }
        }
    }

    private fun boton(t: String, color: Int, alto: Int = 56, accion: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        textSize = if (alto < 56) 15f else 17f
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(12).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(alto)).apply { topMargin = dp(12) }
        setOnClickListener { accion() }
    }

    private fun avisar(t: String) {
        ui.post { aviso.text = t }
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
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(if (url.contains("://")) url else "http://$url")))
        } catch (e: Exception) {
            avisar("No se pudo abrir el navegador.")
        }
    }

    // ---------------- Estado ----------------

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
                            stats.remove(e.id)
                            ultimoStats.remove(e.id)
                            ui.post { statsTv[e.id]?.text = "" }
                        } else if (e.so == "linux" && System.currentTimeMillis() - (ultimoStats[e.id] ?: 0L) > 30000) {
                            ultimoStats[e.id] = System.currentTimeMillis()
                            val t = leerStats(e, h)
                            if (t != null) {
                                stats[e.id] = t
                                ui.post { statsTv[e.id]?.text = t }
                            }
                        }
                    }
                }
                hilos.forEach { it.join() }
                Thread.sleep(3000)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        generacion++
    }

    private fun puerto(host: String, p: Int, ms: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, p), ms); true }
    } catch (e: Exception) {
        false
    }

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

    // ---------------- Estado del servidor (CPU, RAM, temperatura, discos) ----------------

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
        val partes = mutableListOf<String>()
        val linea2 = mutableListOf<String>()
        for (l in r.texto.lines()) {
            val p = l.trim().split(Regex("\\s+"))
            when (p.getOrNull(0)) {
                "C" -> if (p.size >= 3) partes += "Carga ${p[1]} (${p[2]} hilos)"
                "M" -> {
                    val u = p.getOrNull(1)?.toDoubleOrNull()
                    val t = p.getOrNull(2)?.toDoubleOrNull()
                    if (u != null && t != null && t > 0) partes += String.format("RAM %.1f/%.1f GB", u / 1024, t / 1024)
                }
                "T" -> p.getOrNull(1)?.toIntOrNull()?.let { if (it > 0) partes += "${it / 1000} °C" }
                "D" -> if (p.size >= 3) linea2 += "Disco ${p[1]} ${p[2]}"
                "U" -> p.getOrNull(1)?.toLongOrNull()?.let { seg ->
                    val d = seg / 86400
                    val h = (seg % 86400) / 3600
                    val m = (seg % 3600) / 60
                    linea2 += "lleva " + when {
                        d > 0 -> "$d d $h h"
                        h > 0 -> "$h h $m min"
                        else -> "$m min"
                    }
                }
            }
        }
        val l2 = linea2.joinToString(" · ")
        return partes.joinToString(" · ") + if (l2.isNotEmpty()) "\n$l2" else ""
    }

    // ---------------- Contenedores Docker ----------------

    private fun contenedores(e: Equipo) {
        avisar("${e.nombre}: leyendo contenedores…")
        thread {
            val r = ssh(e.usuario, hostDe(e), "docker ps -a --format '{{.Names}}|{{.Status}}'")
            if (!r.ok) {
                avisar("${e.nombre}: no se pudieron leer los contenedores: ${r.texto}" + pista(r.texto))
                return@thread
            }
            val lista = r.texto.lines().mapNotNull { l ->
                val p = l.split("|", limit = 2)
                val n = p[0].trim()
                if (p.size == 2 && Regex("^[A-Za-z0-9_.-]+$").matches(n)) n to p[1].trim() else null
            }.sortedBy { it.first }
            ui.post {
                aviso.text = ""
                if (isFinishing) return@post
                val textos = lista.map { (n, st) ->
                    (if (st.startsWith("Up")) "🟢  " else "🔴  ") + n + "  ·  " + estadoDocker(st)
                }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("Contenedores de ${e.nombre}")
                    .setItems(textos) { _, i -> accionContenedor(e, lista[i].first, lista[i].second.startsWith("Up")) }
                    .setNegativeButton("Cerrar", null)
                    .show()
            }
        }
    }

    private fun estadoDocker(st: String) = when {
        st.startsWith("Up") -> "en marcha" + if (st.contains("unhealthy")) " (con problemas)" else ""
        st.startsWith("Exited") -> "parado"
        st.startsWith("Restarting") -> "reiniciándose"
        st.startsWith("Created") -> "creado"
        else -> st
    }

    private fun accionContenedor(e: Equipo, nombre: String, enMarcha: Boolean) {
        val acciones = if (enMarcha) arrayOf("Reiniciar", "Parar") else arrayOf("Arrancar")
        AlertDialog.Builder(this)
            .setTitle(nombre)
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
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------------- Actualizaciones ----------------

    private fun versionActual(): Int = try {
        (packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0").substringAfter(".").toIntOrNull() ?: 0
    } catch (e: Exception) {
        0
    }

    private fun buscarActualizacion(manual: Boolean) {
        if (manual) avisar("Buscando actualización…")
        thread {
            try {
                val c = URL("https://api.github.com/repos/locometopa/mando-servidor/releases/latest").openConnection() as HttpURLConnection
                c.setRequestProperty("User-Agent", "MandoServidor")
                c.setRequestProperty("Accept", "application/vnd.github+json")
                c.connectTimeout = 8000
                c.readTimeout = 8000
                val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                val nueva = j.optString("tag_name").substringAfter(".").toIntOrNull() ?: 0
                val url = j.optJSONArray("assets")?.optJSONObject(0)?.optString("browser_download_url") ?: ""
                val actual = versionActual()
                if (nueva > actual && url.isNotEmpty()) {
                    ui.post {
                        if (isFinishing) return@post
                        aviso.text = ""
                        AlertDialog.Builder(this)
                            .setTitle("Nueva versión 1.$nueva")
                            .setMessage("Tienes la 1.$actual. ¿La descargas ahora? Se instala encima sin perder tus equipos ni contraseñas.")
                            .setPositiveButton("Descargar") { _, _ -> abrir(url) }
                            .setNegativeButton("Más tarde", null)
                            .show()
                    }
                } else if (manual) {
                    avisar("Ya tienes la última versión (1.$actual).")
                }
            } catch (e: Exception) {
                if (manual) avisar("No se pudo comprobar: ${e.message}")
            }
        }
    }

    // ---------------- Encender ----------------

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
            for (d in destinos) {
                for (port in listOf(7, 9)) {
                    try {
                        s.send(DatagramPacket(p, p.size, InetAddress.getByName(d), port))
                    } catch (e: Exception) {
                    }
                }
            }
        }
    }

    private fun encender(e: Equipo) {
        val mac = macBytes(e.mac)
        if (mac == null) {
            mensaje("Falta la MAC", "Pon la MAC de «${e.nombre}» en Editar.")
            return
        }
        avisar("${e.nombre}: enviando la señal de encendido…")
        thread {
            try {
                enviarWol(mac, e.lan)
            } catch (x: Exception) {
            }
            val enCasa = listOf(445, 5000, 5001).any { puerto(nas("nasLan"), it, 600) }
            if (enCasa) {
                avisar("${e.nombre}: señal enviada por la red de casa.\nTarda 1-2 minutos en arrancar.")
                return@thread
            }
            avisar("${e.nombre}: fuera de casa, enviando la señal a través del NAS…")
            val m = e.mac.trim()
            val i = nas("nasInterfaz")
            val r = ejecutarLinux(
                nas("nasUsuario"), nas("nasTs"),
                "if [ -x /usr/syno/sbin/synonet ]; then SUDO /usr/syno/sbin/synonet --wake $m $i; else SUDO /usr/syno/bin/synonet --wake $m $i; fi",
                "pwNas"
            )
            avisar(
                if (r.ok) "${e.nombre}: señal enviada a través del NAS.\nTarda 1-2 minutos en arrancar."
                else "No se pudo usar el NAS: ${r.texto}" + pista(r.texto)
            )
        }
    }

    // ---------------- Apagar / reiniciar ----------------

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
            avisar(if (r.ok || cortado(r.texto)) "${e.nombre}: orden enviada." else "${e.nombre}: error: ${r.texto}" + pista(r.texto))
        }
    }

    private fun cortado(t: String) =
        t.contains("closed", true) || t.contains("código -1") || t.contains("reset", true)

    private fun esAuth(t: String) = t.contains("Auth fail", true) || t.contains("Auth cancel", true)

    private fun pista(t: String): String = when {
        esAuth(t) -> "\n\nRevisa el usuario y la contraseña en Editar."
        t.contains("password", true) -> "\n\nGuarda la contraseña en Editar para que pueda usar sudo."
        t.contains("timeout", true) || t.contains("connect", true) -> "\n\n¿Está encendido y conectado? Si estás fuera, activa Tailscale."
        else -> ""
    }

    /** Orden en Linux: primero con la llave del móvil; si hace falta, con la contraseña guardada. */
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

    // ---------------- SSH ----------------

    private fun archivoClave() = File(filesDir, "clave_ssh")

    private fun clavePublica(): String {
        val priv = archivoClave()
        val pub = File(filesDir, "clave_ssh.pub")
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
        if (clave == null) {
            clavePublica()
            j.addIdentity(archivoClave().absolutePath)
        }
        val s = j.getSession(usuario, host, 22)
        s.setConfig("StrictHostKeyChecking", "no")
        if (clave != null) {
            s.setPassword(clave)
            s.setConfig("PreferredAuthentications", "password,keyboard-interactive")
        } else {
            s.setConfig("PreferredAuthentications", "publickey")
        }
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
                c.setOutputStream(salida)
                c.setErrStream(salida)
                val stdin = c.outputStream
                c.connect(8000)
                if (entrada != null) {
                    stdin.write(entrada.toByteArray())
                    stdin.flush()
                }
                stdin.close()
                var t = 0
                while (!c.isClosed && t < 150) {
                    Thread.sleep(100)
                    t++
                }
                val code = c.exitStatus
                c.disconnect()
                Resultado(code == 0, salida.toString().trim().ifEmpty { "código $code" })
            } finally {
                s.disconnect()
            }
        } catch (e: Exception) {
            Resultado(false, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun instalarLlave(pub: String, previo: String) =
        "${previo}mkdir -p ~/.ssh && chmod 700 ~/.ssh && touch ~/.ssh/authorized_keys && " +
            "(grep -qF '$pub' ~/.ssh/authorized_keys || echo '$pub' >> ~/.ssh/authorized_keys) && chmod 600 ~/.ssh/authorized_keys"

    /** Instala la llave del móvil y el permiso de sudo en un equipo Linux o en el NAS, usando la contraseña. */
    private fun autorizar(nombre: String, usuario: String, host: String, pw: String, previo: String, sudoers: String) {
        thread {
            val pub = try {
                clavePublica()
            } catch (e: Exception) {
                avisar("$nombre: no se pudo crear la llave: ${e.message}")
                return@thread
            }
            avisar("$nombre: autorizando este móvil…")
            val r1 = ssh(usuario, host, instalarLlave(pub, previo), pw)
            val r2 = if (r1.ok) ssh(usuario, host, "sudo -S -p '' sh -c \"$sudoers\"", pw, pw + "\n") else r1
            avisar("$nombre: " + if (r1.ok && r2.ok) "móvil autorizado ✔" else "no se pudo autorizar (${r2.texto})" + pista(r2.texto))
        }
    }

    // ---------------- Contraseñas cifradas (Android Keystore) ----------------

    private fun claveAes(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry("mando_pw", null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(
            KeyGenParameterSpec.Builder("mando_pw", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return g.generateKey()
    }

    private fun guardarClave(nombre: String, valor: String) {
        try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, claveAes())
            val datos = c.iv + c.doFinal(valor.toByteArray())
            prefs.edit().putString(nombre, Base64.encodeToString(datos, Base64.NO_WRAP)).apply()
        } catch (e: Exception) {
            avisar("No se pudo guardar la contraseña: ${e.message}")
        }
    }

    private fun leerClave(nombre: String): String? {
        val s = prefs.getString(nombre, null) ?: return null
        return try {
            val b = Base64.decode(s, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, claveAes(), GCMParameterSpec(128, b, 0, 12))
            String(c.doFinal(b, 12, b.size - 12))
        } catch (e: Exception) {
            null
        }
    }

    // ---------------- Diálogos ----------------

    private fun etiqueta(caja: LinearLayout, t: String) {
        caja.addView(TextView(this).apply {
            text = t
            textSize = 13f
            setPadding(0, dp(10), 0, 0)
        })
    }

    private fun campo(caja: LinearLayout, t: String, valor: String, pista: String = ""): EditText {
        etiqueta(caja, t)
        val e = EditText(this).apply {
            setText(valor)
            hint = pista
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine(true)
        }
        caja.addView(e)
        return e
    }

    private fun campoClave(caja: LinearLayout, t: String, guardada: Boolean): EditText {
        etiqueta(caja, t + if (guardada) " (guardada)" else "")
        val e = EditText(this).apply {
            hint = if (guardada) "En blanco = no cambiarla" else ""
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        caja.addView(e)
        return e
    }

    private fun nuevaCaja() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(8), dp(20), dp(8))
    }

    private fun editarEquipo(orig: Equipo?) {
        val caja = nuevaCaja()
        val fNombre = campo(caja, "Nombre", orig?.nombre ?: "", "Ej. PC del salón")

        etiqueta(caja, "Tipo de equipo")
        val grupo = RadioGroup(this)
        val opciones = listOf("linux" to "Linux / servidor", "windows" to "Windows", "wol" to "Solo encender")
        val ids = HashMap<Int, String>()
        for ((clave, texto) in opciones) {
            val rb = RadioButton(this).apply { text = texto; id = View.generateViewId() }
            ids[rb.id] = clave
            grupo.addView(rb)
            if (clave == (orig?.so ?: "windows")) grupo.check(rb.id)
        }
        caja.addView(grupo)

        val fMac = campo(caja, "MAC (de la tarjeta de red por cable)", orig?.mac ?: "", "Ej. 80:ee:73:aa:bb:cc")
        val fLan = campo(caja, "IP en casa", orig?.lan ?: "192.168.1.", "Ej. 192.168.1.159")
        val fTs = campo(caja, "IP de Tailscale (opcional)", orig?.ts ?: "", "Ej. 100.79.163.44")
        val fUsu = campo(caja, "Usuario (para apagar y reiniciar)", orig?.usuario ?: "", "Usuario de Linux o de Windows")
        val fPw = campoClave(caja, "Contraseña", orig != null && leerClave("pw_${orig.id}") != null)
        val fWeb = campo(caja, "Dirección web (opcional)", orig?.web ?: "", "Ej. http://192.168.1.180/")
        caja.addView(TextView(this).apply {
            textSize = 12f
            setTextColor(GRIS)
            setPadding(0, dp(12), 0, 0)
            text = "Windows: para apagar y reiniciar, activa «Servidor OpenSSH» en Configuración → Sistema → Características opcionales, y usa tu usuario y contraseña de Windows.\n\n" +
                "Linux: al guardar con contraseña, el móvil se autoriza solo (llave + permiso para apagar).\n\n" +
                "Para encender: el equipo debe ir por cable y tener activado Wake-on-LAN en la BIOS y en la tarjeta de red."
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
                    mac.isNotEmpty() && macBytes(mac) == null -> mensaje("MAC incorrecta", "Ejemplo de MAC: 80:ee:73:aa:bb:cc")
                    else -> {
                        val e = Equipo(
                            id = orig?.id ?: ("e" + System.currentTimeMillis()),
                            nombre = nombre, so = so, mac = mac,
                            lan = fLan.text.toString().trim().removeSuffix("."),
                            ts = fTs.text.toString().trim(),
                            usuario = fUsu.text.toString().trim(),
                            web = fWeb.text.toString().trim(),
                        )
                        equipos = if (orig == null) equipos + e else equipos.map { if (it.id == e.id) e else it }
                        guardar()
                        val pw = fPw.text.toString()
                        if (pw.isNotEmpty()) {
                            guardarClave("pw_${e.id}", pw)
                            if (so == "linux" && e.usuario.isNotEmpty()) {
                                autorizar(
                                    e.nombre, e.usuario, hostDe(e), pw, "",
                                    "echo '${e.usuario} ALL=(root) NOPASSWD: /usr/sbin/poweroff, /usr/sbin/reboot' > /etc/sudoers.d/mando && chmod 440 /etc/sudoers.d/mando"
                                )
                            }
                        }
                        vivos.remove(e.id)
                        pintar()
                        d.dismiss()
                    }
                }
            }
            if (orig != null) {
                d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    confirmar("¿Borrar ${orig.nombre}?", "Solo se quita de la app.") {
                        equipos = equipos.filter { it.id != orig.id }
                        guardar()
                        prefs.edit().remove("pw_${orig.id}").apply()
                        vivos.remove(orig.id)
                        pintar()
                        d.dismiss()
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
            text = "Fuera de casa, el NAS (siempre encendido) envía la señal de encendido a tus equipos. Necesitas Tailscale activo en el móvil y SSH activado en el NAS (Panel de control → Terminal y SNMP)."
        })
        val cajas = LinkedHashMap<String, EditText>()
        for ((k, v) in nasCampos) cajas[k] = campo(caja, v.first, nas(k))
        val fPw = campoClave(caja, "Contraseña del NAS", leerClave("pwNas") != null)
        AlertDialog.Builder(this)
            .setTitle("Ajustes del NAS")
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
                        val u = nas("nasUsuario")
                        autorizar(
                            "NAS", u, h, pw, "chmod 755 ~ && ",
                            "echo '$u ALL=(root) NOPASSWD: /usr/syno/sbin/synonet, /usr/syno/bin/synonet' > /etc/sudoers.d/despertar && chmod 440 /etc/sudoers.d/despertar"
                        )
                    }
                } else {
                    avisar("Ajustes del NAS guardados.")
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}
