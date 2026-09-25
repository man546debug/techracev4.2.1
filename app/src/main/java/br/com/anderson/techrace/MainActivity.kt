package br.com.anderson.techrace

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.*
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.util.concurrent.Executors
import java.util.concurrent.CancellationException
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.sin

class MainActivity : Activity() {
    private enum class ProgrammingMode(val selector: Int, val flagMask: Int, val label: String) {
        SONDA_RPM(1, 0x02, "Sonda / RPM"),
        MAP(2, 0x01, "Sensor MAP")
    }

    private val actionUsbPermission = "br.com.anderson.techrace.USB_PERMISSION"
    private lateinit var usbManager: UsbManager
    private lateinit var dashboard: DashboardView
    // All port open/read/write/close operations belong to the same serial executor.
    private var port: UsbSerialPort? = null
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var generation = 0
    @Volatile private var foreground = false
    private var connected = false
    private var polling = false
    private var busy = false
    private var demoMode = false
    private var demoTick = 0L
    private var pendingDeviceId: Int? = null
    private var activeDeviceId: Int? = null
    private var firmwareVersion = "Não consultada"
    private var settingsSnapshot: ModuleSettings? = null
    private var settingsTime = "--"
    private var moduleReport = "Nenhuma consulta realizada."
    private var snapshotTime = "--"
    private val csvRows = java.util.ArrayDeque<String>()
    private var pendingExport: String? = null
    private var lastRx = byteArrayOf()
    private var lastError = "Nenhuma leitura realizada"
    private var lastValid = "--"
    private var lastData: TechRaceLiveData? = null
    private var pollingIntervalMs = 250L
    private var rpmCalibration = 1.0
    private var programmingMode: ProgrammingMode? = null
    private var programmingDialog: AlertDialog? = null
    private var programmingStatusView: TextView? = null
    private var programmingStartedAt = 0L
    private var resumePollingAfterProgramming = false
    private val programmingStatusTask = Runnable {
        programmingMode?.let { queryProgrammingStatus(it) }
    }
    private val preferences by lazy { getSharedPreferences("techrace_settings", MODE_PRIVATE) }
    private val pollTask = Runnable { if (polling) pollOnce() }
    private val demoTask = object : Runnable {
        override fun run() {
            if (!demoMode || !foreground) return
            updateDemoData()
            handler.postDelayed(this, 250L)
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val device = if (Build.VERSION.SDK_INT >= 33)
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            }
            if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                if (device?.deviceId == activeDeviceId) {
                    disconnect()
                    toast("USB desconectado")
                }
                return
            }
            if (intent.action != actionUsbPermission || device?.deviceId != pendingDeviceId) return
            pendingDeviceId = null
            if (!foreground || demoMode) return
            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                openDevice(device ?: return)
            else toast("Permissão USB negada")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(6, 9, 11)
        window.navigationBarColor = Color.rgb(6, 9, 11)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val filter = IntentFilter(actionUsbPermission).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, filter)
        }
        rpmCalibration = preferences.getInt("rpm_factor", 1).toDouble()
        pollingIntervalMs = preferences.getLong("poll_ms", 250).coerceIn(80, 2000)
        dashboard = DashboardView(this).apply {
            onUsbClick = { if (demoMode) showConfig() else findAndConnect() }
            onNavClick = { nav ->
                when (nav) {
                    DashboardView.Nav.MONITOR -> Unit
                    DashboardView.Nav.AJUSTES -> showModuleMenu()
                    DashboardView.Nav.PROGRAMACAO -> showProgrammingMenu()
                    DashboardView.Nav.DIAGNOSTICO -> showDiagnostics()
                    DashboardView.Nav.CONFIG -> showConfig()
                }
            }
        }
        setContentView(dashboard)
        if (preferences.getBoolean("demo_mode", false)) {
            AlertDialog.Builder(this).setTitle("Retomar demonstração?")
                .setMessage("Serão exibidos dados simulados, sem comunicação USB.")
                .setPositiveButton("Retomar") { _, _ -> setDemoMode(true) }
                .setNegativeButton("Modo real") { _, _ ->
                    preferences.edit().putBoolean("demo_mode", false).apply()
                }.setCancelable(false).show()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Keep the USB session alive while the phone rotates; only the dashboard is reflowed.
        dashboard.requestLayout()
        dashboard.invalidate()
    }

    override fun onStart() {
        super.onStart()
        foreground = true
        if (demoMode) queueDemoStart()
    }

    override fun onStop() {
        foreground = false
        handler.removeCallbacks(demoTask)
        disconnect()
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        ioExecutor.shutdown() // finish the already queued close operation
        unregisterReceiver(receiver)
        super.onDestroy()
    }

    private fun valid(token: Int) = token == generation && foreground
    private fun closePort() {
        try { port?.close() } catch (_: Exception) {}
        port = null
    }

    private fun disconnect() {
        generation++
        polling = false
        busy = false
        connected = false
        pendingDeviceId = null
        activeDeviceId = null
        handler.removeCallbacks(pollTask)
        handler.removeCallbacks(programmingStatusTask)
        programmingMode = null
        resumePollingAfterProgramming = false
        programmingStatusView = null
        programmingDialog?.dismiss()
        programmingDialog = null
        dashboard.connected = false
        dashboard.autoReading = false
        dashboard.clearDemoData()
        lastRx = byteArrayOf()
        lastData = null
        lastValid = "--"
        lastError = "Desconectado"
        firmwareVersion = "Não consultada"
        settingsSnapshot = null
        settingsTime = "--"
        snapshotTime = "--"
        moduleReport = "Nenhuma consulta nesta conexão."
        ioExecutor.execute { closePort() }
    }

    private fun showProgrammingMenu() {
        val state = when {
            demoMode -> "MODO DEMONSTRAÇÃO — nenhum comando será transmitido."
            connected -> "USB conectado • firmware $firmwareVersion"
            else -> "USB desconectado — conecte o módulo antes de programar."
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 20, 36, 8)
            addView(TextView(this@MainActivity).apply {
                text = state
                setPadding(0, 0, 0, 18)
            })
            addView(TextView(this@MainActivity).apply {
                text = "Program.cpp confirma: seletor 1 = Sonda/RPM, seletor 2 = MAP e seletor 0 = encerrar/resetar a programação."
                setPadding(0, 0, 0, 12)
            })
        }
        val items = arrayOf(
            "Programar Sonda / RPM",
            "Programar Sensor MAP",
            "Encerrar / resetar modo de programação",
            "Ver protocolo de programação"
        )
        AlertDialog.Builder(this).setTitle("Programação TechRace")
            .setView(box)
            .setItems(items) { _, i ->
                when (i) {
                    0 -> beginProgramming(ProgrammingMode.SONDA_RPM)
                    1 -> beginProgramming(ProgrammingMode.MAP)
                    2 -> confirmProgrammingStop()
                    3 -> showText("Protocolo confirmado — Program.cpp", """
                        Serial: 19200 8N1
                        Quadro: F3 | CMD | ADDR_H | ADDR_L | LEN | DATA | CRC-8
                        CRC-8: polinômio 0x07, init 0x00

                        Sonda / RPM:
                        ${TechRaceProtocol.toHex(TechRaceProtocol.PROGRAM_SONDA_RPM)}

                        MAP:
                        ${TechRaceProtocol.toHex(TechRaceProtocol.PROGRAM_MAP)}

                        Encerrar / resetar:
                        ${TechRaceProtocol.toHex(TechRaceProtocol.PROGRAM_STOP)}

                        A resposta é aceita como no Send_command do EXE: precisa existir e ter CRC residual zero.
                        O status é acompanhado pela EEPROM: bit 0x02 para Sonda/RPM e bit 0x01 para MAP.
                    """.trimIndent())
                }
            }.setNegativeButton("Fechar", null).show()
    }

    private fun beginProgramming(mode: ProgrammingMode) {
        if (demoMode) {
            val tx = if (mode == ProgrammingMode.SONDA_RPM)
                TechRaceProtocol.PROGRAM_SONDA_RPM else TechRaceProtocol.PROGRAM_MAP
            AlertDialog.Builder(this)
                .setTitle("${mode.label} — demonstração")
                .setMessage("Tela de programação disponível. Em modo demonstração nenhum byte é enviado.\n\nTX real que seria usado:\n${TechRaceProtocol.toHex(tx)}")
                .setPositiveButton("OK", null).show()
            return
        }
        if (!connected || !foreground) {
            toast("Conecte o módulo por USB antes de programar")
            return
        }
        if (busy || programmingMode != null) {
            toast("Há uma operação em andamento")
            return
        }
        val instructions = if (mode == ProgrammingMode.SONDA_RPM) {
            "O comando original inicia a rotina Sonda/RPM. Mantenha o motor em condição estável e use a tela de status para acompanhar a flag 0x02 da EEPROM."
        } else {
            "O comando original inicia a rotina do sensor MAP. Mantenha o motor em condição estável e use a tela de status para acompanhar a flag 0x01 da EEPROM."
        }
        AlertDialog.Builder(this)
            .setTitle("Iniciar ${mode.label}?")
            .setMessage("$instructions\n\nA telemetria contínua será pausada durante a programação. A sequência visual do antigo FlexProgram.cpp não foi fornecida, portanto o APK não inventa etapas adicionais.")
            .setPositiveButton("Iniciar programação") { _, _ -> sendProgrammingStart(mode) }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun sendProgrammingStart(mode: ProgrammingMode) {
        if (busy || demoMode || !connected || !foreground) return
        resumePollingAfterProgramming = polling
        polling = false
        dashboard.autoReading = false
        handler.removeCallbacks(pollTask)
        handler.removeCallbacks(programmingStatusTask)
        busy = true
        val token = generation
        val tx = if (mode == ProgrammingMode.SONDA_RPM)
            TechRaceProtocol.PROGRAM_SONDA_RPM else TechRaceProtocol.PROGRAM_MAP
        ioExecutor.execute {
            var rx = byteArrayOf()
            var failure: String? = null
            try {
                rx = rawExchange(tx, token, attempts = 3)
                check(TechRaceProtocol.isValidAck(rx)) { "Sem ACK válido da central" }
            } catch (e: Exception) {
                failure = e.message ?: "Falha USB"
            }
            val err = failure
            handler.post {
                if (!valid(token) || demoMode) return@post
                busy = false
                if (err != null) {
                    lastError = "Programação ${mode.label}: $err"
                    dashboard.markCommunicationFailure()
                    toast(lastError)
                    if (resumePollingAfterProgramming) {
                        polling = true
                        dashboard.autoReading = true
                        handler.postDelayed(pollTask, pollingIntervalMs)
                    }
                    resumePollingAfterProgramming = false
                } else {
                    programmingMode = mode
                    programmingStartedAt = SystemClock.elapsedRealtime()
                    showProgrammingProgress(mode, tx, rx)
                    handler.postDelayed(programmingStatusTask, 700L)
                }
            }
        }
    }

    private fun showProgrammingProgress(mode: ProgrammingMode, tx: ByteArray, rx: ByteArray) {
        programmingDialog?.dismiss()
        val status = TextView(this).apply {
            setPadding(36, 24, 36, 24)
            text = buildString {
                append("Comando de início aceito pela central.\n\n")
                append("Rotina: ${mode.label}\n")
                append("TX: ${TechRaceProtocol.toHex(tx)}\n")
                append("RX: ${TechRaceProtocol.toHex(rx)}\n\n")
                append("Aguardando leitura da EEPROM para acompanhar o status...")
            }
            setTextIsSelectable(true)
        }
        programmingStatusView = status
        val dialog = AlertDialog.Builder(this)
            .setTitle("Programando ${mode.label}")
            .setView(ScrollView(this).apply { addView(status) })
            .setPositiveButton("Finalizar / encerrar", null)
            .setNeutralButton("Consultar agora", null)
            .setCancelable(false)
            .create()
        programmingDialog = dialog
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (busy) toast("Aguarde a consulta atual terminar") else sendProgrammingStop(closeDialog = true)
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                if (busy) toast("Consulta em andamento") else programmingMode?.let { queryProgrammingStatus(it) }
            }
        }
        dialog.show()
    }

    private fun queryProgrammingStatus(mode: ProgrammingMode) {
        if (programmingMode != mode || demoMode || !connected || !foreground) return
        if (busy) {
            handler.postDelayed(programmingStatusTask, 500L)
            return
        }
        busy = true
        val token = generation
        val tx = TechRaceProtocol.READ_SETTINGS
        ioExecutor.execute {
            var rx = byteArrayOf()
            var payload: ByteArray? = null
            var failure: String? = null
            try {
                rx = rawExchange(tx, token, attempts = 2)
                payload = TechRaceProtocol.extractResponse(rx, 1, 25)
                check(payload != null) { "Resposta EEPROM inválida" }
            } catch (e: Exception) {
                failure = e.message ?: "Falha USB"
            }
            val data = payload
            val err = failure
            handler.post {
                if (!valid(token) || programmingMode != mode) return@post
                busy = false
                if (err != null || data == null) {
                    programmingStatusView?.text = "Programação ${mode.label} iniciada, mas a consulta de status falhou: ${err ?: "resposta inválida"}.\n\nUse 'Consultar agora' para tentar novamente ou 'Finalizar / encerrar' para enviar o seletor 0."
                    return@post
                }
                val settings = ModuleSettings(data)
                settingsSnapshot = settings
                settingsTime = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                dashboard.updateProgrammingFlags(settings.mapFlag, settings.rpmFlag)
                val flagActive = (data[0].toInt() and 0xFF and mode.flagMask) != 0
                val elapsed = (SystemClock.elapsedRealtime() - programmingStartedAt) / 1000
                programmingStatusView?.text = buildString {
                    append("Rotina: ${mode.label}\n")
                    append("Tempo desde o comando: ${elapsed}s\n")
                    append("EEPROM FLAGS: 0x${String.format(java.util.Locale.US, "%02X", data[0].toInt() and 0xFF)}\n")
                    append("Status ${mode.label}: ${if (flagActive) "FLAG ATIVA" else "aguardando flag"}\n\n")
                    if (flagActive) {
                        append("A central informa o bit associado a esta programação como ativo. Esse bit também pode refletir uma calibração anterior; o arquivo FlexProgram.cpp não foi fornecido para confirmar o critério exato de término.\n\n")
                    } else {
                        append("A rotina permanece em acompanhamento. Não desligue alimentação nem desconecte o USB enquanto estiver programando.\n\n")
                    }
                    append("Último RX EEPROM: ${TechRaceProtocol.toHex(rx)}")
                }
                if (!flagActive && elapsed < 90) {
                    handler.removeCallbacks(programmingStatusTask)
                    handler.postDelayed(programmingStatusTask, 1500L)
                }
            }
        }
    }

    private fun confirmProgrammingStop() {
        if (demoMode) {
            toast("Demonstração: nenhum comando enviado")
            return
        }
        if (!connected || !foreground) {
            toast("Conecte o módulo por USB")
            return
        }
        if (busy) {
            toast("Há uma operação em andamento")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Encerrar modo de programação?")
            .setMessage("Será enviado o seletor 0 do Program.cpp: ${TechRaceProtocol.toHex(TechRaceProtocol.PROGRAM_STOP)}")
            .setPositiveButton("Enviar") { _, _ -> sendProgrammingStop(closeDialog = false) }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun sendProgrammingStop(closeDialog: Boolean) {
        if (busy || demoMode || !connected || !foreground) return
        handler.removeCallbacks(programmingStatusTask)
        val token = generation
        val tx = TechRaceProtocol.PROGRAM_STOP
        busy = true
        ioExecutor.execute {
            var rx = byteArrayOf()
            var failure: String? = null
            try {
                rx = rawExchange(tx, token, attempts = 3)
                check(TechRaceProtocol.isValidAck(rx)) { "Sem ACK válido da central" }
            } catch (e: Exception) {
                failure = e.message ?: "Falha USB"
            }
            val err = failure
            handler.post {
                if (!valid(token) || demoMode) return@post
                busy = false
                if (err != null) {
                    programmingStatusView?.text = "Falha ao enviar encerramento: $err\n\nA programação não foi marcada como encerrada no APK."
                    toast("Falha ao encerrar programação")
                    return@post
                }
                programmingMode = null
                programmingStatusView?.text = "Modo de programação encerrado.\n\nTX: ${TechRaceProtocol.toHex(tx)}\nRX: ${TechRaceProtocol.toHex(rx)}"
                if (closeDialog) {
                    programmingDialog?.dismiss()
                    programmingDialog = null
                    programmingStatusView = null
                }
                toast("Modo de programação encerrado")
                if (resumePollingAfterProgramming) {
                    polling = true
                    dashboard.autoReading = true
                    handler.postDelayed(pollTask, pollingIntervalMs)
                }
                resumePollingAfterProgramming = false
            }
        }
    }

    /** Replicates Porta_serial.cpp::Send_command timing/retry behavior for write commands. */
    private fun rawExchange(tx: ByteArray, token: Int, attempts: Int = 3): ByteArray {
        val p = port ?: error("Porta fechada")
        val buffer = ByteArray(128)
        var last = byteArrayOf()
        repeat(attempts.coerceIn(1, 3)) {
            val drainDeadline = SystemClock.elapsedRealtime() + 300
            while (p.read(buffer, 30) > 0) {
                if (!valid(token)) throw CancellationException()
                check(SystemClock.elapsedRealtime() < drainDeadline) { "USB sem intervalo ocioso" }
            }
            if (!valid(token)) throw CancellationException()
            p.write(tx, 500)
            val out = ByteArrayOutputStream()
            val deadline = SystemClock.elapsedRealtime() + 900
            while (SystemClock.elapsedRealtime() < deadline) {
                if (!valid(token)) throw CancellationException()
                val n = p.read(buffer, 40)
                if (n > 0) {
                    out.write(buffer, 0, n)
                    if (out.size() > 128) break
                } else if (out.size() > 0) {
                    break
                }
            }
            last = out.toByteArray()
            if (TechRaceProtocol.isValidAck(last)) return last
        }
        return last
    }

    private fun showAdjustments() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 16, 36, 16)
        }
        val factors = listOf(1, 2, 4, 8)
        val factor = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, factors)
            setSelection(factors.indexOf(rpmCalibration.toInt()).coerceAtLeast(0))
        }
        val interval = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(pollingIntervalMs.toString())
        }
        box.addView(TextView(this).apply { text = "Fator RPM (como no EXE):" })
        box.addView(factor)
        box.addView(TextView(this).apply { text = "Pausa entre leituras (80–2000 ms):" })
        box.addView(interval)
        AlertDialog.Builder(this).setTitle("Ajustes locais — sem gravação no módulo")
            .setView(box).setNegativeButton("Cancelar", null)
            .setPositiveButton("Salvar") { _, _ ->
                rpmCalibration = factors[factor.selectedItemPosition].toDouble()
                pollingIntervalMs = interval.text.toString().toLongOrNull()?.coerceIn(80, 2000) ?: 250
                preferences.edit().putInt("rpm_factor", rpmCalibration.toInt())
                    .putLong("poll_ms", pollingIntervalMs).apply()
            }.show()
    }

    private fun showConfig() {
        val items = arrayOf(
            if (demoMode) "Desativar modo demonstração" else "Ativar modo demonstração",
            if (connected) "Reconectar USB OTG" else "Conectar USB OTG",
            if (polling) "Parar leitura contínua" else "Iniciar leitura contínua",
            "Ler uma vez", "Desconectar USB", "Sobre a V2.4.1", "Consultar módulo / EEPROM", "Exportar telemetria CSV", "Limpar histórico CSV"
        )
        AlertDialog.Builder(this).setTitle("Configurações / Settings").setItems(items) { _, index ->
            when (index) {
                0 -> if (demoMode) setDemoMode(false) else AlertDialog.Builder(this)
                    .setTitle("Ativar demonstração?")
                    .setMessage("Os valores serão simulados. A conexão USB será encerrada.")
                    .setPositiveButton("Ativar") { _, _ -> setDemoMode(true) }
                    .setNegativeButton("Cancelar", null).show()
                1 -> findAndConnect()
                2 -> {
                    if (demoMode || !connected) toast("Conecte o USB em modo real")
                    else {
                        polling = !polling
                        dashboard.autoReading = polling
                        handler.removeCallbacks(pollTask)
                        if (polling && !busy) handler.post(pollTask)
                    }
                }
                3 -> if (demoMode || !connected) toast("Conecte o USB em modo real") else pollOnce()
                4 -> if (!demoMode) disconnect()
                5 -> AlertDialog.Builder(this).setTitle("TechRace V2.4.1 — USB + programação")
                    .setMessage("Protocolo baseado em Principal.cpp, Porta_serial.cpp, Program.cpp e RAM FLEXV10_0. Leitura USB e comandos reais de programação Sonda/RPM e MAP. CRC verificado; validar a rotina no módulo físico antes de uso definitivo.")
                    .setPositiveButton("OK", null).show()
                6 -> showModuleMenu()
                7 -> exportDocument("techrace-telemetria.csv", "text/csv", csvContent())
                8 -> { csvRows.clear(); toast("Histórico CSV limpo") }
            }
        }.show()
    }

    private fun setDemoMode(enabled: Boolean) {
        handler.removeCallbacks(demoTask)
        disconnect()
        demoMode = enabled
        dashboard.demoMode = false
        preferences.edit().putBoolean("demo_mode", enabled).apply()
        demoTick = 0
        if (enabled) queueDemoStart()
    }

    private fun queueDemoStart() {
        val token = generation
        // Place demo start AFTER pending IO/close: no in-flight USB operation in demo.
        ioExecutor.execute {
            handler.post {
                if (valid(token) && demoMode) {
                    handler.removeCallbacks(demoTask)
                    dashboard.demoMode = true
                    demoTask.run()
                }
            }
        }
    }

    private fun updateDemoData() {
        val t = demoTick++ * 0.25
        val cycle = t % 18
        val throttle = when {
            cycle < 3 -> 0.0
            cycle < 7 -> (cycle - 3) / 4
            cycle < 11 -> 1.0
            cycle < 15 -> (15 - cycle) / 4
            else -> 0.0
        }
        val wave = sin(t * 2 * PI / 3)
        val simulated = TechRaceLiveData(
                850 + throttle * 3550 + wave * 25,
                2.15 + throttle * 4.7 + wave * 0.12,
                30 + sin(t * 0.65) * 3,
                0, 80, 100,
                0.72 + throttle * 1.6 + wave * 0.04,
                (450 + sin(t * 2.8) * 340).toInt()
            )
        dashboard.updateDemoData(simulated, (35 + t * 0.9).toInt().coerceAtMost(88), 80)
        appendCsv(simulated, true)
    }

    private fun findAndConnect() {
        if (demoMode) { toast("Desative a demonstração para conectar"); return }
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (drivers.isEmpty()) { toast("Nenhum USB-serial detectado"); return }
        if (drivers.size == 1) requestDevice(drivers.first().device)
        else AlertDialog.Builder(this).setTitle("Escolha o conversor USB")
            .setItems(drivers.map { "${it.device.deviceName} (VID ${it.device.vendorId})" }.toTypedArray()) { _, i ->
                requestDevice(drivers[i].device)
            }.show()
    }

    private fun requestDevice(device: UsbDevice) {
        if (demoMode || !foreground) return
        if (usbManager.hasPermission(device)) openDevice(device)
        else {
            pendingDeviceId = device.deviceId
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            usbManager.requestPermission(device, PendingIntent.getBroadcast(
                this, 0, Intent(actionUsbPermission).setPackage(packageName), flags
            ))
        }
    }

    private fun openDevice(device: UsbDevice) {
        if (demoMode || !foreground) return
        disconnect()
        val token = generation
        activeDeviceId = device.deviceId
        ioExecutor.execute {
            if (!valid(token)) return@execute
            try {
                val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
                    ?: error("Conversor não reconhecido")
                val connection = usbManager.openDevice(device) ?: error("USB indisponível")
                try {
                    port = driver.ports.first()
                    port!!.open(connection)
                    port!!.setParameters(19200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                } catch (e: Exception) {
                    closePort()
                    connection.close()
                    throw e
                }
                if (!valid(token)) { closePort(); return@execute }
                handler.post {
                    if (valid(token) && !demoMode) {
                        connected = true
                        dashboard.connected = true
                        polling = true
                        dashboard.autoReading = true
                        queryModule(0) // Read firmware version before first live query.
                    }
                }
            } catch (e: Exception) {
                closePort()
                handler.post {
                    if (valid(token)) {
                        lastError = "Erro USB: ${e.message}"
                        dashboard.markCommunicationFailure()
                        toast(lastError)
                    }
                }
            }
        }
    }

    private fun pollOnce() {
        if (busy || demoMode || !connected || !foreground) return
        handler.removeCallbacks(pollTask)
        busy = true
        val token = generation
        ioExecutor.execute {
            if (!valid(token)) return@execute
            val response = LiveResponseBuffer()
            var failure: String? = null
            try {
                val p = port ?: error("Porta fechada")
                val buffer = ByteArray(128)
                // Drain stale bytes to an idle gap before the next request, bounded in time.
                val drainDeadline = SystemClock.elapsedRealtime() + 300
                while (p.read(buffer, 30) > 0) {
                    if (!valid(token)) throw CancellationException()
                    check(SystemClock.elapsedRealtime() < drainDeadline) { "USB sem intervalo ocioso" }
                }
                if (!valid(token)) throw CancellationException()
                p.write(TechRaceProtocol.READ_LIVE_10, 500)
                val deadline = SystemClock.elapsedRealtime() + 800
                while (SystemClock.elapsedRealtime() < deadline) {
                    if (!valid(token)) throw CancellationException()
                    val n = p.read(buffer, 40)
                    if (n > 0) {
                        response.append(buffer.copyOf(n))
                        if (response.snapshot().size > 13) break
                    } else if (response.snapshot().isNotEmpty()) break // inter-byte idle gap
                }
                val rx = response.snapshot()
                failure = when {
                    rx.isEmpty() -> "Sem resposta (timeout)"
                    rx.size != 13 -> "Resposta com ${rx.size} bytes; esperado: 13"
                    response.payload() == null -> "CRC inválido"
                    else -> null
                }
            } catch (e: Exception) {
                failure = e.message ?: "Falha de leitura"
            }
            handler.post {
                if (valid(token) && !demoMode) {
                    busy = false
                    lastRx = response.snapshot()
                    val payload = response.payload()
                    if (failure == null && payload != null) {
                        val data = TechRaceDecoder.decode(payload, rpmCalibration)
                        lastData = data
                        dashboard.updateData(data)
                        appendCsv(data, false)
                        lastError = "Nenhum"
                        lastValid = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                            .format(java.util.Date())
                    } else {
                        lastError = failure ?: "Resposta inválida"
                        dashboard.markCommunicationFailure()
                        polling = false // stop on error, like the desktop
                        dashboard.autoReading = false
                        toast("Leitura parada: $lastError")
                    }
                    if (polling) handler.postDelayed(pollTask, pollingIntervalMs)
                }
            }
        }
    }

    private fun showDiagnostics() {
        val data = lastData
        val body = """
            Modo: ${if (demoMode) "DEMO — sem USB" else "REAL — USB"}
            APK: 2.4.1
            Programação ativa: ${programmingMode?.label ?: "não"}
            Firmware: $firmwareVersion
            Porta: ${if (connected) "ABERTA" else "FECHADA"}
            Serial: 19200 8N1
            Leitura contínua: $polling
            Última leitura válida: $lastValid
            Erros: ${dashboard.errorCount}
            Último erro: $lastError
            Intervalo interno (RAM 47): ${data?.raw4 ?: "--"}
            Mistura interna Y_PERCENT (RAM 49): ${data?.raw6 ?: "--"}
            ADC temperatura: ${data?.temperatureRaw ?: "--"}

            TX (${TechRaceProtocol.READ_LIVE_10.size} bytes):
            ${TechRaceProtocol.toHex(TechRaceProtocol.READ_LIVE_10)}

            RX (${lastRx.size} bytes):
            ${TechRaceProtocol.toHex(lastRx)}

            Resposta esperada: 2 bytes iniciais + 10 dados + CRC.
            Cabeçalho: endereço do módulo + função (Comunicação PC.xls).
            Endereço de resposta registrado, sem valor fixo presumido.
            CRC e tamanho válidos não substituem validação com o módulo.

            Consulta adicional ($snapshotTime):
            $moduleReport
        """.trimIndent()
        val view = TextView(this).apply {
            text = body
            setPadding(32, 20, 32, 20)
            setTextIsSelectable(true)
        }
        AlertDialog.Builder(this).setTitle("Diagnóstico USB")
            .setView(ScrollView(this).apply { addView(view) })
            .setPositiveButton("Fechar", null)
            .setNeutralButton("Exportar TXT") { _, _ -> exportDocument("techrace-diagnostico.txt", "text/plain", body) }.show()
    }

    private fun showModuleMenu() {
        val items = arrayOf("Consultar versão do firmware", "Ler configurações EEPROM",
            "Ver última configuração lida", "RAM adicional (experimental, valores brutos)",
            "Fator RPM e intervalo de leitura", "Exportar telemetria CSV")
        AlertDialog.Builder(this).setTitle("Módulo — somente leitura").setItems(items) { _, i ->
            when (i) {
                0 -> queryModule(0)
                1 -> queryModule(1)
                2 -> showText("EEPROM — $settingsTime", settingsSnapshot?.describe(rpmCalibration)
                    ?: "Nenhuma configuração foi lida nesta conexão.")
                3 -> AlertDialog.Builder(this).setTitle("Mapa RAM a validar")
                    .setMessage("Consulta 0x4D..0x56 segundo a planilha FLEX V10.0. A compatibilidade com firmware 2.0 não foi confirmada. Os dados serão mostrados como valores brutos.")
                    .setPositiveButton("Consultar") { _, _ -> queryModule(2) }
                    .setNegativeButton("Cancelar", null).show()
                4 -> showAdjustments()
                5 -> exportDocument("techrace-telemetria.csv", "text/csv", csvContent())
            }
        }.show()
    }

    /** All additional requests use the existing single-threaded USB executor. */
    private fun queryModule(kind: Int) {
        if (demoMode || !connected || !foreground) { toast("Conecte o USB em modo real"); return }
        if (busy) { toast("Leitura em andamento; tente novamente"); return }
        val tx = when (kind) {
            0 -> TechRaceProtocol.READ_VERSION
            1 -> TechRaceProtocol.READ_SETTINGS
            else -> TechRaceProtocol.READ_EXTENDED
        }
        val expected = when (kind) { 0 -> 2; 1 -> 25; else -> 10 }
        val command = tx[1].toInt() and 255
        val token = generation
        busy = true
        handler.removeCallbacks(pollTask)
        ioExecutor.execute {
            val received = LiveResponseBuffer()
            var queryFailure: String? = null
            var payload: ByteArray? = null
            try {
                val p = port ?: error("Porta fechada")
                val buf = ByteArray(128)
                val drainDeadline = SystemClock.elapsedRealtime() + 300
                while (p.read(buf, 30) > 0) {
                    if (!valid(token)) throw CancellationException()
                    check(SystemClock.elapsedRealtime() < drainDeadline) { "USB sem intervalo ocioso" }
                }
                if (!valid(token)) throw CancellationException()
                p.write(tx, 500)
                val deadline = SystemClock.elapsedRealtime() + 800
                while (SystemClock.elapsedRealtime() < deadline) {
                    if (!valid(token)) throw CancellationException()
                    val n = p.read(buf, 40)
                    if (n > 0) {
                        received.append(buf.copyOf(n))
                        if (received.snapshot().size > expected + 3) break
                    } else if (received.snapshot().isNotEmpty()) break
                }
                payload = TechRaceProtocol.extractResponse(received.snapshot(), command, expected)
                check(payload != null) { "Resposta inválida: tamanho, função ou CRC; esperado ${expected + 3} bytes" }
            } catch (e: Exception) { queryFailure = e.message ?: "Falha USB" }
            val result = payload
            val failure = queryFailure
            handler.post {
                if (!valid(token) || demoMode) return@post
                busy = false
                snapshotTime = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                val wire = "TX: ${TechRaceProtocol.toHex(tx)}\nRX: ${TechRaceProtocol.toHex(received.snapshot())}"
                if (failure != null || result == null) {
                    moduleReport = "Falha na consulta: $failure\n$wire"
                    if (kind == 0) firmwareVersion = "Consulta falhou"
                    if (kind == 1) settingsSnapshot = null
                    polling = false
                    dashboard.autoReading = false
                    dashboard.markCommunicationFailure()
                    toast("Consulta falhou; veja Diagnóstico. Leitura contínua pausada.")
                } else {
                    when (kind) {
                        0 -> {
                            firmwareVersion = "${result[0].toInt() and 255}.${result[1].toInt() and 255}"
                            moduleReport = "Firmware informado: $firmwareVersion\n$wire"
                            toast("Firmware do módulo: $firmwareVersion")
                        }
                        1 -> {
                            settingsSnapshot = ModuleSettings(result)
                            settingsTime = snapshotTime
                            dashboard.updateProgrammingFlags(settingsSnapshot!!.mapFlag, settingsSnapshot!!.rpmFlag)
                            moduleReport = settingsSnapshot!!.describe(rpmCalibration) + "\n" + wire
                            showText("EEPROM — firmware $firmwareVersion", moduleReport)
                        }
                        else -> {
                            fun u(i: Int) = result[i].toInt() and 255
                            moduleReport = """
                                RAM adicional — valores brutos, mapa a validar
                                POT_ATUAL 0x4D: ${u(0)}
                                MAP_LENTA 0x4E: ${u(1)}
                                MAP_CARGA 0x4F: ${u(2)}
                                BICO_LENTA 0x50..51: ${u(3) + 256 * u(4)}
                                BICO_CARGA 0x52..53: ${u(5) + 256 * u(6)}
                                FATOR_MAP 0x54: ${u(7)}
                                LEITURAS_SONDA 0x55: ${u(8)}
                                FINAL_RAM 0x56: ${u(9)}
                                $wire
                            """.trimIndent()
                            showText("RAM adicional", moduleReport)
                        }
                    }
                    if (polling) handler.postDelayed(pollTask, pollingIntervalMs)
                }
            }
        }
    }

    private fun showText(title: String, body: String) {
        val view = TextView(this).apply { text = body; setPadding(32, 20, 32, 20); setTextIsSelectable(true) }
        AlertDialog.Builder(this).setTitle(title).setView(ScrollView(this).apply { addView(view) })
            .setPositiveButton("Fechar", null)
            .setNeutralButton("Exportar TXT") { _, _ -> exportDocument("techrace-consulta.txt", "text/plain", body) }.show()
    }

    private fun appendCsv(data: TechRaceLiveData, demo: Boolean) {
        if (csvRows.size >= 10000) csvRows.removeFirst()
        val temp = if (demo) dashboard.temperatureC else TechRaceDecoder.temperatureC(data.temperatureRaw)
        csvRows.addLast(listOf(System.currentTimeMillis(), if (demo) "DEMO" else "REAL",
            if (demo) "SIMULADO" else firmwareVersion.replace(';', '_'), rpmCalibration,
            data.rpm, data.injectionMs, data.correctionPercent, data.mapVoltage, data.lambdaMv,
            temp ?: "", data.raw6, data.raw4, data.temperatureRaw).joinToString(";"))
    }

    private fun csvContent() = "timestamp_unix_ms;modo;firmware;fator_rpm;rpm;injecao_ms;correcao_pct;map_v;sonda_mv;temperatura_c;y_percent;intervalo_raw;temperatura_adc\n" + csvRows.joinToString("\n")

    @Suppress("DEPRECATION")
    private fun exportDocument(name: String, mime: String, content: String) {
        if (pendingExport != null) { toast("Exportação já em andamento"); return }
        pendingExport = content
        try {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = mime
                putExtra(Intent.EXTRA_TITLE, name)
            }, 230)
        } catch (e: Exception) { pendingExport = null; toast("Não foi possível abrir o seletor de arquivos") }
    }

    @Deprecated("Activity callback retained for minSdk 24")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 230) return
        val content = pendingExport
        pendingExport = null
        if (resultCode != RESULT_OK || content == null) return
        val uri = data?.data ?: return
        try {
            val stream = contentResolver.openOutputStream(uri, "wt") ?: error("Arquivo indisponível")
            stream.bufferedWriter(Charsets.UTF_8).use { it.write(content) }
            toast("Arquivo exportado")
        } catch (e: Exception) { toast("Falha ao exportar: ${e.message}") }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
