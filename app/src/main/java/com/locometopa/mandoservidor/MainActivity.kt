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
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import com.jcraft.jsch.Session
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.concurrent.thread

class MainActivity : Activity() {

    companion object {
        val FONDO = Color.rgb(30, 32, 36)
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

    class Resultado(val ok: Boolean, val texto: String)

    private lateinit var prefs: SharedPreferences
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var estado: TextView
    private lateinit var info: TextView
    private lateinit var aviso: TextView

    @Volatile private var vivo: String? = null
    @Volatile private var generacion = 0

    // clave -> (etiqueta, valor por defecto)
    private val campos = linkedMapOf(
        "mac" to ("MAC del servidor (ej. 80:ee:73:aa:bb:cc)" to ""),
        "usuario" to ("Usuario del servidor" to "locometopa"),
        "servidorLan" to ("IP del servidor (casa)" to "192.168.1.180"),
        "servidorTs" to ("IP del servidor (Tailscale)" to "100.124.132.5"),
        "nasUsuario" to ("Usuario del NAS" to "locometopa"),
        "nasLan" to ("IP del NAS (casa)" to "192.168.1.150"),
        "nasTs" to ("IP del NAS (Tailscale)" to "100.127.53.156"),
        "nasInterfaz" to ("Interfaz de red del NAS" to "eth0"),
    )

    private fun cfg(k: String): String =
        prefs.getString(k, null)?.trim()?.takeIf { it.isNotEmpty() } ?: campos[k]!!.second

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---------------- Interfaz ----------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("mando", MODE_PRIVATE)
        window.statusBarColor = FONDO
        window.navigationBarColor = FONDO

        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(FONDO)
            setPadding(dp(20), dp(28), dp(20), dp(28))
        }
        raiz.addView(TextView(this).apply {
            text = "Servidor de casa"
            setTextColor(Color.WHITE)
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        })
        estado = TextView(this).apply {
            text = "●  Comprobando…"
            setTextColor(AMARILLO)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, dp(4))
        }
        info = TextView(this).apply {
            setTextColor(GRIS)
            textSize = 14f
            gravity = Gravity.CENTER
        }
        aviso = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(8))
        }
        raiz.addView(estado)
        raiz.addView(info)
        raiz.addView(aviso)

        raiz.addView(boton("Encender", VERDE) { encender() })
        raiz.addView(boton("Reiniciar", NARANJA) {
            confirmar("¿Reiniciar el servidor?", "Los servicios se cortarán un par de minutos.") {
                ordenServidor("SUDO /usr/sbin/reboot", "Reiniciando el servidor…")
            }
        })
        raiz.addView(boton("Apagar", ROJO) {
            confirmar("¿Apagar el servidor?", "Si estás fuera de casa perderás el acceso a la red de casa hasta que lo enciendas.") {
                ordenServidor("SUDO /usr/sbin/poweroff", "Apagando el servidor…")
            }
        })

        val fila = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fila.addView(boton("Página de inicio", AZUL) { abrir("http://${vivo ?: cfg("servidorLan")}/") },
            LinearLayout.LayoutParams(0, dp(56), 1f).apply { marginEnd = dp(6); topMargin = dp(12) })
        fila.addView(boton("Cockpit", AZUL) { abrir("https://${vivo ?: cfg("servidorLan")}:9090/") },
            LinearLayout.LayoutParams(0, dp(56), 1f).apply { marginStart = dp(6); topMargin = dp(12) })
        raiz.addView(fila)

        raiz.addView(boton("Autorizar este móvil", GRIS_OSC) { autorizar() })
        raiz.addView(boton("Ajustes", GRIS_OSC) { ajustes() })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(FONDO)
            addView(raiz)
        })

        if (prefs.getString("mac", "").isNullOrBlank()) {
            avisar("Primero entra en Ajustes y pon la MAC del servidor.")
        }
    }

    private fun boton(t: String, color: Int, accion: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        textSize = 17f
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(12).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)).apply { topMargin = dp(12) }
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
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
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
                comprobar()
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

    private fun comprobar() {
        val lan = cfg("servidorLan")
        val ts = cfg("servidorTs")
        val h = when {
            puerto(lan, 22, 800) -> lan
            puerto(ts, 22, 2000) -> ts
            else -> null
        }
        vivo = h
        ui.post {
            if (h != null) {
                estado.text = "●  Encendido"
                estado.setTextColor(VERDE_CLARO)
                info.text = "Responde por " + (if (h == lan) "la red de casa" else "Tailscale") + " ($h)"
            } else {
                estado.text = "●  Apagado o sin conexión"
                estado.setTextColor(ROJO_CLARO)
                info.text = "No responde ni en casa ni por Tailscale"
            }
        }
    }

    // ---------------- Encender ----------------

    private fun macBytes(): ByteArray? {
        val h = cfg("mac").replace(":", "").replace("-", "")
        if (!Regex("^[0-9A-Fa-f]{12}$").matches(h)) return null
        return ByteArray(6) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private fun enviarWol(mac: ByteArray) {
        val p = ByteArray(102)
        for (i in 0 until 6) p[i] = 0xFF.toByte()
        for (r in 1..16) System.arraycopy(mac, 0, p, r * 6, 6)
        DatagramSocket().use { s ->
            s.broadcast = true
            for (d in listOf("255.255.255.255", "192.168.1.255")) {
                for (port in listOf(7, 9)) {
                    try {
                        s.send(DatagramPacket(p, p.size, InetAddress.getByName(d), port))
                    } catch (e: Exception) {
                    }
                }
            }
        }
    }

    private fun encender() {
        val mac = macBytes()
        if (mac == null) {
            mensaje("Falta la MAC", "Pon la MAC del servidor en Ajustes.")
            return
        }
        avisar("Enviando la señal de encendido…")
        thread {
            try {
                enviarWol(mac)
            } catch (e: Exception) {
            }
            val enCasa = listOf(445, 5000, 5001).any { puerto(cfg("nasLan"), it, 600) }
            if (enCasa) {
                avisar("Señal enviada por la red de casa.\nTarda 1-2 minutos en arrancar.")
                return@thread
            }
            avisar("Fuera de casa: enviando la señal a través del NAS…")
            val m = cfg("mac")
            val i = cfg("nasInterfaz")
            val r = ejecutar(
                cfg("nasUsuario"), cfg("nasTs"),
                "if [ -x /usr/syno/sbin/synonet ]; then SUDO /usr/syno/sbin/synonet --wake $m $i; else SUDO /usr/syno/bin/synonet --wake $m $i; fi",
                "pwNas"
            )
            avisar(
                if (r.ok) "Señal enviada a través del NAS.\nTarda 1-2 minutos en arrancar."
                else "No se pudo usar el NAS: ${r.texto}" + pista(r.texto)
            )
        }
    }

    // ---------------- Apagar / reiniciar ----------------

    private fun ordenServidor(cmd: String, txt: String) {
        avisar(txt)
        thread {
            val h = vivo ?: cfg("servidorLan")
            val r = ejecutar(cfg("usuario"), h, cmd, "pwServidor")
            avisar(if (r.ok || cortado(r.texto)) "Orden enviada." else "Error: ${r.texto}" + pista(r.texto))
        }
    }

    private fun cortado(t: String) =
        t.contains("closed", true) || t.contains("código -1") || t.contains("reset", true)

    private fun esAuth(t: String) = t.contains("Auth fail", true) || t.contains("Auth cancel", true)

    // Ejecuta una orden: primero con la llave del móvil; si hace falta, con la contraseña guardada.
    private fun ejecutar(usuario: String, host: String, plantilla: String, pwClave: String): Resultado {
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

    private fun pista(t: String): String =
        if (t.contains("Auth fail", true) || t.contains("password", true) || t.contains("contraseña", true))
            "\n\nPulsa «Autorizar este móvil»."
        else ""

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

    // ---------------- Autorizar ----------------

    private fun campoClave(pista: String) = EditText(this).apply {
        hint = pista
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    }

    private fun autorizar() {
        val caja = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        caja.addView(TextView(this).apply {
            text = "El móvil instala su propia llave en el servidor y en el NAS. Las contraseñas se guardan cifradas en el móvil como respaldo.\n\nPara el NAS tienes que estar en casa o con Tailscale activo."
            textSize = 14f
        })
        val ps = campoClave("Contraseña del servidor")
        val pn = campoClave("Contraseña del NAS (opcional)")
        caja.addView(ps)
        caja.addView(pn)
        AlertDialog.Builder(this)
            .setTitle("Autorizar este móvil")
            .setView(caja)
            .setPositiveButton("Autorizar") { _, _ -> hacerAutorizar(ps.text.toString(), pn.text.toString()) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun instalarLlave(pub: String, previo: String) =
        "${previo}mkdir -p ~/.ssh && chmod 700 ~/.ssh && touch ~/.ssh/authorized_keys && " +
            "(grep -qF '$pub' ~/.ssh/authorized_keys || echo '$pub' >> ~/.ssh/authorized_keys) && chmod 600 ~/.ssh/authorized_keys"

    private fun hacerAutorizar(ps: String, pn: String) {
        if (ps.isNotEmpty()) guardarClave("pwServidor", ps)
        if (pn.isNotEmpty()) guardarClave("pwNas", pn)
        avisar("Autorizando… (la primera vez tarda unos segundos en crear la llave)")
        thread {
            val pub = try {
                clavePublica()
            } catch (e: Exception) {
                avisar("No se pudo crear la llave: ${e.message}")
                return@thread
            }
            val partes = mutableListOf<String>()
            if (ps.isNotEmpty()) {
                val u = cfg("usuario")
                val h = vivo ?: cfg("servidorLan")
                val r1 = ssh(u, h, instalarLlave(pub, ""), ps)
                val r2 = if (r1.ok) ssh(
                    u, h,
                    "sudo -S -p '' sh -c \"echo '$u ALL=(root) NOPASSWD: /usr/sbin/poweroff, /usr/sbin/reboot' > /etc/sudoers.d/mando && chmod 440 /etc/sudoers.d/mando\"",
                    ps, ps + "\n"
                ) else r1
                partes += "Servidor: " + if (r1.ok && r2.ok) "autorizado ✔" else "error (${r2.texto})"
            }
            if (pn.isNotEmpty()) {
                val u = cfg("nasUsuario")
                val h = if (listOf(445, 5000, 5001).any { puerto(cfg("nasLan"), it, 600) }) cfg("nasLan") else cfg("nasTs")
                val r1 = ssh(u, h, instalarLlave(pub, "chmod 755 ~ && "), pn)
                val r2 = if (r1.ok) ssh(
                    u, h,
                    "sudo -S -p '' sh -c \"echo '$u ALL=(root) NOPASSWD: /usr/syno/sbin/synonet, /usr/syno/bin/synonet' > /etc/sudoers.d/despertar && chmod 440 /etc/sudoers.d/despertar\"",
                    pn, pn + "\n"
                ) else r1
                partes += "NAS: " + if (r1.ok && r2.ok) "autorizado ✔" else "error (${r2.texto})"
            }
            avisar(if (partes.isEmpty()) "No has escrito ninguna contraseña." else partes.joinToString("\n"))
        }
    }

    // ---------------- Ajustes ----------------

    private fun ajustes() {
        val caja = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val cajas = mutableMapOf<String, EditText>()
        for ((k, v) in campos) {
            caja.addView(TextView(this).apply {
                text = v.first
                textSize = 13f
                setPadding(0, dp(10), 0, 0)
            })
            val e = EditText(this).apply {
                setText(cfg(k))
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                setSingleLine(true)
            }
            caja.addView(e)
            cajas[k] = e
        }
        caja.addView(TextView(this).apply {
            text = "Contraseña del servidor" + (if (leerClave("pwServidor") != null) " (guardada)" else "")
            textSize = 13f
            setPadding(0, dp(10), 0, 0)
        })
        val pwS = campoClave("En blanco = no cambiarla")
        caja.addView(pwS)
        caja.addView(TextView(this).apply {
            text = "Contraseña del NAS" + (if (leerClave("pwNas") != null) " (guardada)" else "")
            textSize = 13f
            setPadding(0, dp(10), 0, 0)
        })
        val pwN = campoClave("En blanco = no cambiarla")
        caja.addView(pwN)
        AlertDialog.Builder(this)
            .setTitle("Ajustes")
            .setView(ScrollView(this).apply { addView(caja) })
            .setPositiveButton("Guardar") { _, _ ->
                val ed = prefs.edit()
                for ((k, e) in cajas) ed.putString(k, e.text.toString().trim())
                ed.apply()
                if (pwS.text.isNotEmpty()) guardarClave("pwServidor", pwS.text.toString())
                if (pwN.text.isNotEmpty()) guardarClave("pwNas", pwN.text.toString())
                if (macBytes() == null) avisar("La MAC no parece correcta (ejemplo: 80:ee:73:aa:bb:cc).")
                else avisar("Ajustes guardados.")
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}
