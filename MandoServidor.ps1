# Mando Servidor - enciende, apaga y reinicia el servidor de casa a distancia
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
[System.Windows.Forms.Application]::EnableVisualStyles()

$dir = Split-Path -Parent $MyInvocation.MyCommand.Path
$cfgPath = Join-Path $dir 'config.json'

$cfg = [ordered]@{
    ServidorLan       = '192.168.1.180'
    ServidorTailscale = '100.124.132.5'
    Usuario           = 'locometopa'
    Mac               = ''
    NasLan            = '192.168.1.150'
    NasTailscale      = '100.127.53.156'
    NasUsuario        = ''
    NasInterfaz       = 'eth0'
}
if (Test-Path $cfgPath) {
    try {
        $j = Get-Content $cfgPath -Raw -Encoding UTF8 | ConvertFrom-Json
        foreach ($k in @($cfg.Keys)) { if ($j.PSObject.Properties[$k]) { $cfg[$k] = [string]$j.$k } }
    } catch { }
}
function Guardar-Config { ($cfg | ConvertTo-Json) | Set-Content $cfgPath -Encoding UTF8 }

function Mac-Valida { $cfg.Mac -match '^([0-9A-Fa-f]{2}[:-]?){5}[0-9A-Fa-f]{2}$' }

function Probar-Puerto([string]$h, [int]$p, [int]$ms = 700) {
    try {
        $c = New-Object Net.Sockets.TcpClient
        $a = $c.BeginConnect($h, $p, $null, $null)
        $ok = $a.AsyncWaitHandle.WaitOne($ms) -and $c.Connected
        $c.Close(); return $ok
    } catch { return $false }
}

function Enviar-WoL {
    $hex = $cfg.Mac -replace '[:-]', ''
    [byte[]]$mb = for ($i = 0; $i -lt 6; $i++) { [Convert]::ToByte($hex.Substring($i * 2, 2), 16) }
    $pkt = New-Object byte[] 102
    for ($i = 0; $i -lt 6; $i++) { $pkt[$i] = 0xFF }
    for ($r = 1; $r -le 16; $r++) { [Array]::Copy($mb, 0, $pkt, $r * 6, 6) }
    $u = New-Object Net.Sockets.UdpClient
    $u.EnableBroadcast = $true
    foreach ($d in '255.255.255.255', '192.168.1.255') {
        foreach ($port in 7, 9) { try { [void]$u.Send($pkt, $pkt.Length, $d, $port) } catch { } }
    }
    $u.Close()
}

function Abrir-Consola([string]$titulo, [string]$orden) {
    $s = "`$Host.UI.RawUI.WindowTitle = '$titulo'`nWrite-Host '$titulo' -ForegroundColor Cyan`nWrite-Host ''`n$orden`nWrite-Host ''`nRead-Host 'Pulsa Enter para cerrar'"
    $enc = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($s))
    Start-Process powershell.exe -ArgumentList "-NoProfile -EncodedCommand $enc"
}

function Host-Servidor {
    if (Probar-Puerto $cfg.ServidorLan 22 500) { return $cfg.ServidorLan }
    return $cfg.ServidorTailscale
}

# ---------- Ventana ----------
$fuente = New-Object Drawing.Font('Segoe UI', 10)
$form = New-Object Windows.Forms.Form
$form.Text = 'Mando del servidor'
$form.ClientSize = New-Object Drawing.Size(360, 300)
$form.StartPosition = 'CenterScreen'
$form.FormBorderStyle = 'FixedSingle'
$form.MaximizeBox = $false
$form.Font = $fuente
$form.BackColor = [Drawing.Color]::FromArgb(30, 32, 36)
$form.ForeColor = [Drawing.Color]::White

$lblEstado = New-Object Windows.Forms.Label
$lblEstado.Text = '●  Comprobando…'
$lblEstado.Font = New-Object Drawing.Font('Segoe UI', 14, [Drawing.FontStyle]::Bold)
$lblEstado.ForeColor = [Drawing.Color]::Gold
$lblEstado.AutoSize = $false
$lblEstado.TextAlign = 'MiddleCenter'
$lblEstado.SetBounds(10, 12, 340, 36)
$form.Controls.Add($lblEstado)

$lblInfo = New-Object Windows.Forms.Label
$lblInfo.Text = ''
$lblInfo.ForeColor = [Drawing.Color]::Silver
$lblInfo.AutoSize = $false
$lblInfo.TextAlign = 'MiddleCenter'
$lblInfo.SetBounds(10, 48, 340, 40)
$form.Controls.Add($lblInfo)

function Nuevo-Boton([string]$texto, [int]$x, [int]$y, [int]$w, [Drawing.Color]$color) {
    $b = New-Object Windows.Forms.Button
    $b.Text = $texto
    $b.SetBounds($x, $y, $w, 44)
    $b.FlatStyle = 'Flat'
    $b.FlatAppearance.BorderSize = 0
    $b.BackColor = $color
    $b.ForeColor = [Drawing.Color]::White
    $b.Cursor = 'Hand'
    $form.Controls.Add($b)
    return $b
}
$bEncender  = Nuevo-Boton 'Encender'  20  100 100 ([Drawing.Color]::FromArgb(46, 125, 50))
$bReiniciar = Nuevo-Boton 'Reiniciar' 130 100 100 ([Drawing.Color]::FromArgb(230, 126, 34))
$bApagar    = Nuevo-Boton 'Apagar'    240 100 100 ([Drawing.Color]::FromArgb(198, 40, 40))
$bInicio    = Nuevo-Boton 'Página de inicio' 20  160 155 ([Drawing.Color]::FromArgb(55, 71, 79))
$bCockpit   = Nuevo-Boton 'Cockpit'          185 160 155 ([Drawing.Color]::FromArgb(55, 71, 79))
$bConfig    = Nuevo-Boton 'Configuración'    20  220 320 ([Drawing.Color]::FromArgb(69, 90, 100))

# ---------- Estado (comprobación sin bloquear la ventana) ----------
$script:pend = @()
$script:vivo = $null
function Comprobar {
    if ($script:pend.Count -gt 0) {
        $script:vivo = $null
        foreach ($p in $script:pend) { if (-not $script:vivo -and $p.C.Connected) { $script:vivo = $p.H } ; $p.C.Close() }
        if ($script:vivo) {
            $lblEstado.Text = '●  Encendido'
            $lblEstado.ForeColor = [Drawing.Color]::LightGreen
            $via = if ($script:vivo -eq $cfg.ServidorLan) { 'red de casa' } else { 'Tailscale' }
            $lblInfo.Text = "Responde por $via ($($script:vivo))"
        } else {
            $lblEstado.Text = '●  Apagado o sin conexión'
            $lblEstado.ForeColor = [Drawing.Color]::Tomato
            $lblInfo.Text = 'No responde ni en casa ni por Tailscale'
        }
    }
    $script:pend = @()
    foreach ($h in @($cfg.ServidorLan, $cfg.ServidorTailscale)) {
        try {
            $c = New-Object Net.Sockets.TcpClient
            [void]$c.BeginConnect($h, 22, $null, $null)
            $script:pend += [pscustomobject]@{ C = $c; H = $h }
        } catch { }
    }
}
$timer = New-Object Windows.Forms.Timer
$timer.Interval = 3000
$timer.Add_Tick({ Comprobar })

# ---------- Acciones ----------
$bEncender.Add_Click({
    if (-not (Mac-Valida)) {
        [Windows.Forms.MessageBox]::Show('Falta la dirección MAC del servidor. Ponla en Configuración.', 'Encender') | Out-Null
        return
    }
    Enviar-WoL
    $enCasa = $false
    foreach ($pt in 445, 5000, 5001) { if (Probar-Puerto $cfg.NasLan $pt 500) { $enCasa = $true; break } }
    if ($enCasa) {
        $lblInfo.Text = 'Señal enviada por la red de casa. Tarda 1-2 minutos en arrancar.'
    } elseif ($cfg.NasUsuario) {
        $lblInfo.Text = 'Fuera de casa: enviando la señal a través del NAS…'
        $dest = "$($cfg.NasUsuario)@$($cfg.NasTailscale)"
        $m = $cfg.Mac; $i = $cfg.NasInterfaz
        $remoto = "if [ -x /usr/syno/sbin/synonet ]; then sudo /usr/syno/sbin/synonet --wake $m $i; else sudo /usr/syno/bin/synonet --wake $m $i; fi && echo 'Señal enviada. El servidor tarda 1-2 minutos en arrancar.'"
        Abrir-Consola 'Encender servidor (a través del NAS)' "ssh -t $dest `"$remoto`""
    } else {
        [Windows.Forms.MessageBox]::Show("Parece que estás fuera de casa. Para encender desde fuera, pon el usuario del NAS en Configuración.", 'Encender') | Out-Null
    }
})

$bApagar.Add_Click({
    $r = [Windows.Forms.MessageBox]::Show("¿Apagar el servidor?`n`nSi estás fuera de casa perderás el acceso a la red de casa hasta que lo enciendas.", 'Apagar', 'YesNo', 'Warning')
    if ($r -eq 'Yes') { Abrir-Consola 'Apagar servidor' "ssh -t $($cfg.Usuario)@$(Host-Servidor) `"sudo poweroff`"" }
})

$bReiniciar.Add_Click({
    $r = [Windows.Forms.MessageBox]::Show('¿Reiniciar el servidor? Los servicios se cortarán un par de minutos.', 'Reiniciar', 'YesNo', 'Question')
    if ($r -eq 'Yes') { Abrir-Consola 'Reiniciar servidor' "ssh -t $($cfg.Usuario)@$(Host-Servidor) `"sudo reboot`"" }
})

$bInicio.Add_Click({  $h = if ($script:vivo) { $script:vivo } else { $cfg.ServidorLan }; Start-Process "http://$h/" })
$bCockpit.Add_Click({ $h = if ($script:vivo) { $script:vivo } else { $cfg.ServidorLan }; Start-Process "https://${h}:9090/" })

$bConfig.Add_Click({
    $f = New-Object Windows.Forms.Form
    $f.Text = 'Configuración'
    $f.Font = $fuente
    $f.StartPosition = 'CenterParent'
    $f.FormBorderStyle = 'FixedDialog'
    $f.MaximizeBox = $false; $f.MinimizeBox = $false
    $campos = [ordered]@{
        Mac = 'MAC del servidor'; Usuario = 'Usuario del servidor'
        ServidorLan = 'IP del servidor (casa)'; ServidorTailscale = 'IP del servidor (Tailscale)'
        NasUsuario = 'Usuario del NAS (para encender desde fuera)'; NasTailscale = 'IP del NAS (Tailscale)'
        NasLan = 'IP del NAS (casa)'; NasInterfaz = 'Interfaz de red del NAS'
    }
    $cajas = @{}; $y = 12
    foreach ($k in $campos.Keys) {
        $l = New-Object Windows.Forms.Label; $l.Text = $campos[$k]; $l.SetBounds(12, $y, 400, 20); $f.Controls.Add($l)
        $t = New-Object Windows.Forms.TextBox; $t.Text = $cfg[$k]; $t.SetBounds(12, $y + 20, 400, 26); $f.Controls.Add($t)
        $cajas[$k] = $t; $y += 52
    }
    $ok = New-Object Windows.Forms.Button; $ok.Text = 'Guardar'; $ok.SetBounds(312, $y + 4, 100, 32); $f.Controls.Add($ok)
    $f.AcceptButton = $ok
    $f.ClientSize = New-Object Drawing.Size(424, ($y + 48))
    $ok.Add_Click({
        foreach ($k in $cajas.Keys) { $cfg[$k] = $cajas[$k].Text.Trim() }
        if ($cfg.Mac -and -not (Mac-Valida)) { [Windows.Forms.MessageBox]::Show('La MAC no parece correcta (ejemplo: 00:1A:2B:3C:4D:5E).', 'Configuración') | Out-Null; return }
        Guardar-Config; $f.Close()
    })
    [void]$f.ShowDialog($form)
})

$form.Add_Shown({ Comprobar; $timer.Start() })
$form.Add_FormClosed({ $timer.Stop() })
[void]$form.ShowDialog()
