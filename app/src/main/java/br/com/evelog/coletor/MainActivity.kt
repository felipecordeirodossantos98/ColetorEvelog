package br.com.evelog.coletor

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.net.Uri
import android.provider.DocumentsContract
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.widget.ArrayAdapter
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private data class LabelBatch(
    val id: String,
    var name: String,
    var operatorName: String,
    var type: String,
    val createdAt: Long,
    var closedAt: Long?,
    val entries: LinkedHashMap<String, Long> = linkedMapOf(),
    var exportedAt: Long? = null,
    var exportError: String? = null
)

class MainActivity : Activity() {
    private val storage by lazy { getSharedPreferences("coletor", MODE_PRIVATE) }
    private val batches = mutableListOf<LabelBatch>()
    private val activeBatch: LabelBatch?
        get() = batches.lastOrNull { it.closedAt == null }

    private lateinit var status: TextView
    private lateinit var batchSummary: TextView
    private lateinit var totalCount: TextView
    private lateinit var recent: TextView
    private lateinit var history: LinearLayout
    private val expandedBatchIds = mutableSetOf<String>()
    private lateinit var batchNameInput: EditText
    private lateinit var operatorNameInput: EditText
    private lateinit var typeLabel: TextView
    private lateinit var typeInput: Spinner
    private lateinit var input: EditText
    private lateinit var startBatchButton: Button
    private lateinit var closeBatchButton: Button
    private lateinit var exportBatchButton: Button
    private var tone: ToneGenerator? = null

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            try {
                val data = intent?.getStringExtra("com.symbol.datawedge.data_string")
                    ?: intent?.getStringExtra("data_string")
                    ?: return
                process(data.trim())
            } catch (error: Exception) {
                android.util.Log.e("ColetorEvelog", "Falha ao processar leitura do DataWedge", error)
                status.text = "ERRO NA LEITURA\n${error.javaClass.simpleName}: ${error.message ?: "sem detalhes"}"
                status.setTextColor(Color.rgb(160, 35, 35))
                status.background = rounded(ERROR_BG, 16f)
                Toast.makeText(this@MainActivity, "Erro ao processar o código", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadBatches()
        tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90)
        buildScreen()
        refreshScreen()
        configureDataWedge()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(SCAN_ACTION).apply { addCategory(Intent.CATEGORY_DEFAULT) }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(scanReceiver, filter, RECEIVER_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(scanReceiver, filter)
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(scanReceiver) } catch (_: IllegalArgumentException) { }
    }

    override fun onDestroy() {
        tone?.release()
        super.onDestroy()
    }

    private fun buildScreen() {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(24))
        }

        val header = FrameLayout(this).apply {
            background = rounded(BRAND_NAVY, 22f)
        }
        val headerContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(16), dp(18), dp(18))
        }
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.evelog_logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Logo Evelog"
        }
        headerContent.addView(logo, LinearLayout.LayoutParams(-1, dp(82)))
        headerContent.addView(label("Coletor Evelog", 23f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        })
        header.addView(headerContent, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        val moreButton = label("⋮", 30f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            contentDescription = "Mais opções"
            isClickable = true
            isFocusable = true
            setOnClickListener { showOverflowMenu(this) }
        }
        header.addView(moreButton, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(4)
            rightMargin = dp(4)
        })
        page.addView(header, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })

        status = label("PRONTO PARA LER", 16f, SUCCESS_TEXT, true).apply {
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = rounded(SUCCESS_BG, 16f)
        }
        page.addView(status, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })

        batchSummary = label("", 17f, TEXT_PRIMARY, true).apply {
            setPadding(dp(16), dp(15), dp(16), dp(15))
            background = rounded(Color.WHITE, 16f, BORDER)
        }
        page.addView(batchSummary, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        totalCount = label("", 14f, TEXT_SECONDARY).apply {
            setPadding(dp(2), 0, dp(2), dp(12))
        }
        page.addView(totalCount)

        batchNameInput = EditText(this).apply {
            hint = "Lote (número da rua)"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter { source, start, end, _, _, _ ->
                if ((start until end).all { source[it] in '0'..'9' }) null else ""
            })
            textSize = 15f
            setPadding(dp(14), dp(13), dp(14), dp(13))
            setHintTextColor(TEXT_MUTED)
            background = rounded(Color.WHITE, 14f, BORDER)
        }
        page.addView(batchNameInput, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        operatorNameInput = EditText(this).apply {
            hint = "Nome do operador"
            setSingleLine(true)
            textSize = 15f
            setPadding(dp(14), dp(13), dp(14), dp(13))
            setHintTextColor(TEXT_MUTED)
            background = rounded(Color.WHITE, 14f, BORDER)
        }
        page.addView(operatorNameInput, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        typeLabel = label("TIPO (OBRIGATÓRIO)", 13f, TEXT_SECONDARY, true).apply {
            setPadding(dp(2), dp(2), 0, dp(6))
        }
        page.addView(typeLabel)
        typeInput = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_item,
                arrayOf("Selecione o tipo", "Transferência", "Entrada")
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setSelection(0)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = rounded(Color.WHITE, 14f, BORDER)
        }
        page.addView(typeInput, LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(10) })

        startBatchButton = actionButton("Iniciar lote", BRAND_BLUE, Color.WHITE, BRAND_BLUE) {
            startBatch()
        }
        page.addView(startBatchButton, LinearLayout.LayoutParams(-1, dp(56)).apply { bottomMargin = dp(10) })

        closeBatchButton = actionButton("Fechar lote", Color.WHITE, BRAND_BLUE, BRAND_BLUE) {
            confirmCloseBatch()
        }
        page.addView(closeBatchButton, LinearLayout.LayoutParams(-1, dp(54)).apply { bottomMargin = dp(10) })

        var applyingManualFormat = false
        input = EditText(this).apply {
            hint = "Leitura manual (opcional)"
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_DONE
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(27))
            textSize = 15f
            setPadding(dp(14), dp(13), dp(14), dp(13))
            setHintTextColor(TEXT_MUTED)
            background = rounded(Color.WHITE, 14f, BORDER)
            setOnEditorActionListener { _, _, event ->
                if (event?.keyCode == KeyEvent.KEYCODE_ENTER || event == null) {
                    val value = text.toString().trim()
                    if (value.isNotEmpty()) process(value)
                    true
                } else false
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    if (applyingManualFormat || s == null) return
                    val formatted = formatManualCode(s.toString()) ?: return
                    if (formatted != s.toString()) {
                        applyingManualFormat = true
                        setText(formatted)
                        setSelection(formatted.length)
                        applyingManualFormat = false
                    }
                }
            })
        }
        page.addView(input, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        exportBatchButton = actionButton("Exportar pendentes", BRAND_BLUE, Color.WHITE, BRAND_BLUE) {
            retryPendingExports()
        }
        page.addView(exportBatchButton, LinearLayout.LayoutParams(-1, dp(56)).apply { bottomMargin = dp(10) })

        page.addView(actionButton("Apagar lotes", Color.WHITE, BRAND_RED, BRAND_RED) {
            showDeleteBatchSelectionDialog()
        }, LinearLayout.LayoutParams(-1, dp(54)).apply { bottomMargin = dp(18) })

        page.addView(label("ETIQUETAS DO LOTE EM SEQUÊNCIA", 13f, TEXT_SECONDARY, true).apply {
            setPadding(dp(2), dp(2), 0, dp(8))
        })
        recent = label("", 14f, TEXT_PRIMARY).apply {
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(Color.WHITE, 16f, BORDER)
        }
        page.addView(recent, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })

        page.addView(label("HISTÓRICO DE LOTES", 13f, TEXT_SECONDARY, true).apply {
            setPadding(dp(2), dp(2), 0, dp(8))
        })
        history = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        page.addView(history, LinearLayout.LayoutParams(-1, -2))

        val screen = ScrollView(this).apply {
            setFillViewport(true)
            setBackgroundColor(SCREEN_BG)
            addView(page)
        }
        setContentView(screen)
    }

    private fun startBatch() {
        if (activeBatch != null) return
        val now = System.currentTimeMillis()
        val requestedName = batchNameInput.text.toString().trim()
        val operator = operatorNameInput.text.toString().trim()
        val selectedType = typeInput.selectedItemPosition.takeIf { it > 0 }?.let { typeInput.selectedItem.toString() }
        if (!requestedName.matches(Regex("^[0-9]+$"))) {
            batchNameInput.error = "Informe o número da rua usando somente números"
            batchNameInput.requestFocus()
            return
        }
        if (operator.isBlank()) {
            operatorNameInput.error = "Informe o nome do operador"
            operatorNameInput.requestFocus()
            return
        }
        if (selectedType == null) {
            Toast.makeText(this, "Selecione Transferência ou Entrada.", Toast.LENGTH_SHORT).show()
            typeInput.performClick()
            return
        }
        batchNameInput.error = null
        operatorNameInput.error = null
        batches.add(LabelBatch(UUID.randomUUID().toString(), requestedName, operator, selectedType, now, null))
        batchNameInput.text.clear()
        operatorNameInput.text.clear()
        typeInput.setSelection(0)
        refreshScreen()
        status.text = "LOTE ABERTO\nLote Rua $requestedName\nOperador: $operator\nTipo: $selectedType\nPode iniciar as leituras."
        status.setTextColor(SUCCESS_TEXT)
        status.background = rounded(SUCCESS_BG, 16f)
        tone?.startTone(ToneGenerator.TONE_PROP_ACK, 140)
    }

    private fun confirmCloseBatch() {
        val batch = activeBatch ?: return
        if (batch.entries.isEmpty()) {
            batches.remove(batch)
            saveBatches()
            refreshScreen()
            status.text = "LOTE VAZIO DESCARTADO\nNenhum registro ou arquivo foi salvo."
            status.setTextColor(BRAND_BLUE)
            status.background = rounded(INFO_BG, 16f)
            return
        }
        val passwordInput = EditText(this).apply {
            hint = "Senha para fechar o lote"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Fechar lote?")
            .setMessage("Lote Rua ${batch.name} será encerrado com ${shipmentCount(batch)} Shipment ID(s) e ${batch.entries.size} etiqueta(s). Um arquivo TXT com as etiquetas em sequência será exportado. Digite a senha para confirmar.")
            .setView(passwordInput)
            .setNegativeButton("Continuar lendo", null)
            .setPositiveButton("Fechar lote", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (passwordInput.text.toString() != ADMIN_PASSWORD) {
                    passwordInput.error = "Senha incorreta"
                    passwordInput.text.clear()
                    return@setOnClickListener
                }
                dialog.dismiss()
                closeBatch(batch)
            }
        }
        dialog.show()
    }

    private fun closeBatch(batch: LabelBatch) {
        batch.closedAt = System.currentTimeMillis()
        saveBatches()
        autoExportBatch(batch)
        refreshScreen()
        val exportStatus = if (batch.exportedAt != null) "1 arquivo exportado" else "exportação pendente"
        status.text = "LOTE FECHADO\nLote Rua ${batch.name}\n${shipmentCount(batch)} Shipment ID(s) · ${batch.entries.size} etiqueta(s) · $exportStatus."
        status.setTextColor(BRAND_BLUE)
        status.background = rounded(INFO_BG, 16f)
        tone?.startTone(ToneGenerator.TONE_PROP_ACK, 140)
    }

    private fun process(raw: String) {
        val scanned = raw.trim().removeSuffix("\r").removeSuffix("\n")
        val code = if (DIGITS_ONLY_CODE.matches(scanned)) {
            scanned.substring(0, 14) + "\$" + scanned.substring(14)
        } else scanned
        val batch = activeBatch
        if (batch == null) {
            showResult("NENHUM LOTE ABERTO", "Inicie um lote antes de ler etiquetas.", false)
            return
        }
        if (!VALID_CODE.matches(code)) {
            showResult("NÃO VÁLIDA", "Formato inválido: $code", false)
            return
        }
        val originalBatch = batches.firstOrNull { it.entries.containsKey(code) }
        if (originalBatch != null) {
            showResult("DUPLICADA", "Etiqueta já registrada no Lote Rua ${originalBatch.name}\n$code", false, duplicate = true)
            return
        }
        batch.entries[code] = System.currentTimeMillis()
        saveBatches()
        refreshBatchDetails()
        refreshRecent()
        refreshHistory()
        refreshBatchControls()
        showResult("VÁLIDA · REGISTRADA", code, true)
    }

    /** Requests DataWedge to create and associate this app's scanner profile. */
    private fun configureDataWedge() {
        val profile = Bundle().apply {
            putString("PROFILE_NAME", "ColetorEvelog")
            putString("PROFILE_ENABLED", "true")
            putString("CONFIG_MODE", "CREATE_IF_NOT_EXIST")

            val barcode = Bundle().apply {
                putString("PLUGIN_NAME", "BARCODE")
                putString("RESET_CONFIG", "true")
                putBundle("PARAM_LIST", Bundle().apply {
                    putString("scanner_input_enabled", "true")
                    putString("scanner_selection", "auto")
                })
            }
            val intentOutput = Bundle().apply {
                putString("PLUGIN_NAME", "INTENT")
                putString("RESET_CONFIG", "true")
                putBundle("PARAM_LIST", Bundle().apply {
                    putString("intent_output_enabled", "true")
                    putString("intent_action", SCAN_ACTION)
                    putString("intent_category", Intent.CATEGORY_DEFAULT)
                    putString("intent_delivery", "2")
                })
            }
            val keystrokeOutput = Bundle().apply {
                putString("PLUGIN_NAME", "KEYSTROKE")
                putString("RESET_CONFIG", "true")
                putBundle("PARAM_LIST", Bundle().apply {
                    putString("keystroke_output_enabled", "false")
                })
            }
            putParcelableArrayList("PLUGIN_CONFIG", arrayListOf(barcode, intentOutput, keystrokeOutput))

            val app = Bundle().apply {
                putString("PACKAGE_NAME", packageName)
                putStringArray("ACTIVITY_LIST", arrayOf("*"))
            }
            putParcelableArray("APP_LIST", arrayOf(app))
        }
        sendBroadcast(Intent(DATAWEDGE_API_ACTION).apply {
            putExtra(DATAWEDGE_SET_CONFIG, profile)
        })
    }

    private fun showResult(title: String, detail: String, valid: Boolean, duplicate: Boolean = false) {
        status.text = "$title\n$detail"
        status.setTextColor(if (valid) SUCCESS_TEXT else ERROR_TEXT)
        status.background = rounded(if (valid) SUCCESS_BG else ERROR_BG, 16f)
        tone?.startTone(if (valid) ToneGenerator.TONE_PROP_ACK else ToneGenerator.TONE_PROP_NACK, 180)
        if (!valid || duplicate) {
            val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createOneShot(180, VibrationEffect.DEFAULT_AMPLITUDE))
            else @Suppress("DEPRECATION") vibrator.vibrate(180)
        }
        input.text.clear()
    }

    private fun refreshScreen() {
        refreshBatchDetails()
        refreshRecent()
        refreshHistory()
        refreshBatchControls()
        if (activeBatch != null) {
            status.text = "LOTE ABERTO\nLote Rua ${activeBatch!!.name}\nPode iniciar as leituras."
            status.setTextColor(SUCCESS_TEXT)
            status.background = rounded(SUCCESS_BG, 16f)
        } else {
            status.text = "NENHUM LOTE ABERTO\nInicie um lote para começar a leitura."
            status.setTextColor(BRAND_BLUE)
            status.background = rounded(INFO_BG, 16f)
        }
    }

    private fun refreshBatchDetails() {
        val active = activeBatch
        batchSummary.text = if (active != null) {
            "LOTE ABERTO · LOTE RUA ${active.name}\nOperador: ${active.operatorName} · Tipo: ${active.type}\n${shipmentCount(active)} Shipment ID(s) · ${active.entries.size} etiqueta(s) no lote"
        } else {
            "Nenhum lote aberto\nInicie um lote para organizar as próximas leituras."
        }
        val totalLabels = batches.sumOf { it.entries.size }
        val totalShipments = batches.sumOf { shipmentCount(it) }
        val closedCount = batches.count { it.closedAt != null }
        totalCount.text = "$totalShipments Shipment ID(s) · $totalLabels etiqueta(s) em ${batches.size} lote(s), $closedCount fechado(s)"
    }

    private fun refreshBatchControls() {
        val hasActive = activeBatch != null
        batchNameInput.visibility = if (hasActive) android.view.View.GONE else android.view.View.VISIBLE
        operatorNameInput.visibility = if (hasActive) android.view.View.GONE else android.view.View.VISIBLE
        typeLabel.visibility = if (hasActive) android.view.View.GONE else android.view.View.VISIBLE
        typeInput.visibility = if (hasActive) android.view.View.GONE else android.view.View.VISIBLE
        startBatchButton.visibility = if (hasActive) android.view.View.GONE else android.view.View.VISIBLE
        closeBatchButton.visibility = if (hasActive) android.view.View.VISIBLE else android.view.View.GONE
        input.visibility = if (hasActive) android.view.View.VISIBLE else android.view.View.GONE
        closeBatchButton.isEnabled = hasActive
        closeBatchButton.alpha = if (closeBatchButton.isEnabled) 1f else 0.5f
        exportBatchButton.isEnabled = pendingExportBatches().isNotEmpty()
        exportBatchButton.alpha = if (exportBatchButton.isEnabled) 1f else 0.5f
    }

    private fun refreshRecent() {
        val batch = activeBatch
        if (batch == null) {
            recent.text = ""
            return
        }
        recent.text = "LOTE RUA ${batch.name} · EM LEITURA\n${shipmentCount(batch)} Shipment ID(s) · ${batch.entries.size} etiqueta(s)\n\n${formatSequentialEntries(batch.entries)}"
    }

    private fun refreshHistory() {
        history.removeAllViews()
        if (batches.isEmpty()) {
            history.addView(label("Nenhum lote registrado ainda.", 14f, TEXT_PRIMARY).apply {
                setPadding(dp(16), dp(14), dp(16), dp(14))
                background = rounded(Color.WHITE, 16f, BORDER)
            })
            return
        }

        batches.asReversed().forEach { batch ->
            val expanded = batch.id in expandedBatchIds
            val state = if (batch.closedAt == null) "ABERTO" else "FECHADO"
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(Color.WHITE, 16f, BORDER)
            }
            val headerText = label(
                "${if (expanded) "▾" else "▸"}  $state · Lote Rua ${batch.name}\nTipo: ${batch.type} · Operador: ${batch.operatorName}\n${shipmentCount(batch)} Shipment ID(s) · ${batch.entries.size} etiqueta(s) · ${batchExportSummary(batch)} · ${formatDate(batch.createdAt)}\n${if (expanded) "Toque para ocultar detalhes" else "Toque para ver etiquetas"}",
                14f,
                TEXT_PRIMARY,
                true
            ).apply {
                setPadding(dp(4), dp(10), dp(14), dp(10))
            }
            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(4), dp(4), dp(4))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (!expandedBatchIds.add(batch.id)) expandedBatchIds.remove(batch.id)
                    refreshHistory()
                }
            }
            val indicator = when {
                batch.exportedAt != null -> Triple("✓", Color.rgb(31, 122, 61), Color.rgb(232, 245, 233))
                batch.closedAt != null -> Triple("✕", Color.rgb(183, 28, 28), Color.rgb(253, 236, 234))
                else -> Triple("·", TEXT_MUTED, SCREEN_BG)
            }
            header.addView(TextView(this).apply {
                text = indicator.first
                textSize = 22f
                gravity = Gravity.CENTER
                setTextColor(indicator.second)
                background = rounded(indicator.third, 20f)
            }, LinearLayout.LayoutParams(dp(36), dp(36)).apply { rightMargin = dp(8) })
            header.addView(headerText, LinearLayout.LayoutParams(0, -2, 1f))
            card.addView(header, LinearLayout.LayoutParams(-1, -2))

            if (expanded) {
                val exportState = when {
                    batch.exportedAt != null -> "EXPORTADO ${formatDate(batch.exportedAt!!)}"
                    batch.exportError != null -> "ERRO NA EXPORTAÇÃO · ${batch.exportError}"
                    batch.closedAt != null -> "AGUARDANDO EXPORTAÇÃO"
                    else -> "SERÁ EXPORTADO AO FECHAR O LOTE"
                }
                card.addView(label("$exportState\n\n${formatSequentialEntries(batch.entries)}", 14f, TEXT_PRIMARY).apply {
                    setPadding(dp(16), dp(8), dp(16), dp(16))
                    typeface = android.graphics.Typeface.MONOSPACE
                }, LinearLayout.LayoutParams(-1, -2))
            }
            history.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
    }

    private fun loadBatches() {
        val savedBatches = storage.getString(BATCHES_KEY, null)
        if (!savedBatches.isNullOrBlank()) {
            try {
                val array = JSONArray(savedBatches)
                for (i in 0 until array.length()) {
                    val row = array.optJSONObject(i) ?: continue
                    val entries = linkedMapOf<String, Long>()
                    val entryArray = row.optJSONArray("entries") ?: JSONArray()
                    for (j in 0 until entryArray.length()) {
                        val entry = entryArray.optJSONObject(j) ?: continue
                        val code = entry.optString("code")
                        if (VALID_CODE.matches(code)) entries[code] = entry.optLong("time")
                    }
                    val id = row.optString("id").ifBlank { UUID.randomUUID().toString() }
                    val storedName = row.optString("name").ifBlank { "Lote recuperado" }
                    val name = if (storedName == "Coleta anterior") "Leituras anteriores" else storedName
                    val operator = row.optString("operator").ifBlank { "Não informado" }
                    val type = row.optString("type").ifBlank { "Não informado" }
                    val closedAt = if (row.isNull("closedAt")) null else row.optLong("closedAt")
                    val exportedAt = if (row.isNull("exportedAt")) null else row.optLong("exportedAt")
                    val exportError = row.optString("exportError").takeIf { it.isNotBlank() }
                    if (entries.isNotEmpty()) {
                        batches.add(LabelBatch(
                            id = id,
                            name = name,
                            operatorName = operator,
                            type = type,
                            createdAt = row.optLong("createdAt", System.currentTimeMillis()),
                            closedAt = closedAt,
                            entries = entries,
                            exportedAt = exportedAt,
                            exportError = exportError
                        ))
                    }
                }
                val openBatches = batches.filter { it.closedAt == null }
                if (openBatches.size > 1) {
                    openBatches.dropLast(1).forEach { it.closedAt = System.currentTimeMillis() }
                    saveBatches()
                }
                return
            } catch (error: Exception) {
                android.util.Log.e("ColetorEvelog", "Falha ao carregar lotes salvos", error)
            }
        }

        // Migra a lista única das versões anteriores para o histórico como um lote fechado.
        val oldArray = try { JSONArray(storage.getString("codes", "[]")) } catch (_: Exception) { JSONArray() }
        val oldEntries = linkedMapOf<String, Long>()
        for (i in 0 until oldArray.length()) {
            val row = oldArray.optJSONObject(i) ?: continue
            val code = row.optString("code")
            if (VALID_CODE.matches(code)) oldEntries[code] = row.optLong("time")
        }
        if (oldEntries.isNotEmpty()) {
            val firstTime = oldEntries.values.minOrNull() ?: System.currentTimeMillis()
            batches.add(LabelBatch(
                UUID.randomUUID().toString(), "Leituras anteriores", "Não informado", "Não informado",
                firstTime, System.currentTimeMillis(), oldEntries
            ))
            saveBatches()
        }
    }

    private fun saveBatches() {
        val array = JSONArray()
        batches.filter { it.entries.isNotEmpty() }.forEach { batch ->
            val entries = JSONArray()
            batch.entries.forEach { (code, time) ->
                entries.put(JSONObject().put("code", code).put("time", time))
            }
            array.put(JSONObject()
                .put("id", batch.id)
                .put("name", batch.name)
                .put("operator", batch.operatorName)
                .put("type", batch.type)
                .put("createdAt", batch.createdAt)
                .put("closedAt", batch.closedAt)
                .put("exportedAt", batch.exportedAt)
                .put("exportError", batch.exportError)
                .put("entries", entries))
        }
        storage.edit().putString(BATCHES_KEY, array.toString()).apply()
    }

    private fun showSettings() {
        val folder = selectedFolderUri()?.let(::folderDisplayName) ?: "Nenhuma pasta selecionada"
        AlertDialog.Builder(this)
            .setTitle("Configurações")
            .setMessage("Pasta de salvamento:\n$folder\n\nO TXT do lote será exportado automaticamente ao fechar.")
            .setNegativeButton("Fechar", null)
            .setNeutralButton("Escolher pasta") { _, _ -> chooseSaveFolder() }
            .setPositiveButton("Limpar pasta") { _, _ ->
                storage.edit().remove(SAVE_FOLDER_URI_KEY).apply()
                Toast.makeText(this, "Pasta de salvamento removida.", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun chooseSaveFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, PICK_FOLDER_REQUEST)
    }

    private fun selectedFolderUri(): Uri? = storage.getString(SAVE_FOLDER_URI_KEY, null)?.let(Uri::parse)

    private fun folderDisplayName(treeUri: Uri): String = try {
        val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        contentResolver.query(documentUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else "Pasta selecionada"
        } ?: "Pasta selecionada"
    } catch (_: Exception) {
        "Pasta selecionada"
    }

    private fun autoExportBatch(batch: LabelBatch) {
        if (batch.entries.isEmpty() || batch.closedAt == null || batch.exportedAt != null) return
        val treeUri = selectedFolderUri()
        if (treeUri == null) {
            batch.exportError = "Pasta não configurada"
            saveBatches()
            Toast.makeText(this, "Lote fechado. Configure a pasta em ⋮ > Configurações para exportar.", Toast.LENGTH_LONG).show()
            return
        }
        try {
            exportBatchToFolder(batch, treeUri)
            val exportedTime = System.currentTimeMillis()
            batch.exportedAt = exportedTime
            batch.exportError = null
        } catch (error: Exception) {
            batch.exportError = error.localizedMessage ?: error.javaClass.simpleName
        }
        saveBatches()
        Toast.makeText(this, if (batch.exportedAt != null) "Arquivo TXT do lote exportado." else "Exportação pendente. Confira Configurações e tente novamente.", Toast.LENGTH_LONG).show()
    }

    private fun exportBatchToFolder(batch: LabelBatch, treeUri: Uri) {
        val parentDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocumentId)
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = listOf(
            safeFilePart(batch.type, "tipo"), "Lote_Rua_${safeFilePart(batch.name, "rua")}",
            safeFilePart(batch.operatorName, "operador"), stamp
        ).joinToString("_") + ".txt"
        val fileUri = DocumentsContract.createDocument(contentResolver, parentUri, "text/plain", fileName)
            ?: throw IllegalStateException("O provedor da pasta não criou o arquivo")
        val content = buildString {
            appendLine("Coletor Evelog · versão ${appVersionName()}")
            appendLine("Tipo: ${batch.type}")
            appendLine("Lote: Rua ${batch.name}")
            appendLine("Operador: ${batch.operatorName}")
            appendLine("Exportado em: ${formatDateTime(System.currentTimeMillis())}")
            appendLine("Shipment IDs: ${shipmentCount(batch)}")
            appendLine("Etiquetas: ${batch.entries.size}")
            appendLine()
            appendLine(formatSequentialEntries(batch.entries))
        }
        contentResolver.openOutputStream(fileUri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(content) }
            ?: throw IllegalStateException("Não foi possível gravar no arquivo criado")
    }

    private fun retryPendingExports() {
        val pendingBatches = pendingExportBatches()
        if (pendingBatches.isEmpty()) {
            Toast.makeText(this, "Não há lotes pendentes para exportar.", Toast.LENGTH_SHORT).show()
            return
        }
        pendingBatches.forEach(::autoExportBatch)
        refreshScreen()
    }

    private fun pendingExportBatches(): List<LabelBatch> = batches.filter { batch ->
        batch.closedAt != null && batch.entries.isNotEmpty() && batch.exportedAt == null
    }

    private fun groupedEntries(batch: LabelBatch): LinkedHashMap<String, MutableList<String>> = groupedEntries(batch.entries)

    private fun groupedEntries(entries: Map<String, Long>): LinkedHashMap<String, MutableList<String>> {
        val grouped = linkedMapOf<String, MutableList<String>>()
        entries.keys.forEach { code ->
            grouped.getOrPut(code.take(14)) { mutableListOf() }.add(code)
        }
        return grouped
    }

    private fun shipmentCount(batch: LabelBatch): Int = groupedEntries(batch).size

    private fun formatSequentialEntries(entries: Map<String, Long>): String = entries.keys
        .joinToString("\n")
        .ifEmpty { "Nenhuma etiqueta neste lote." }

    private fun safeFilePart(value: String, fallback: String): String = value
        .trim()
        .replace(Regex("[^\\p{L}\\p{N}_-]+"), "_")
        .trim('_')
        .ifBlank { fallback }

    @Suppress("DEPRECATION")
    private fun appVersionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "desconhecida"
    } catch (_: Exception) {
        "desconhecida"
    }

    private fun formatDateTime(time: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(time))

    private fun showOverflowMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add("Configurações").setOnMenuItemClickListener {
                showSettings()
                true
            }
            menu.add("Sobre").setOnMenuItemClickListener {
                showAboutDialog()
                true
            }
            show()
        }
    }

    @Suppress("DEPRECATION")
    private fun showAboutDialog() {
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "desconhecida"
        } catch (_: Exception) {
            "desconhecida"
        }
        AlertDialog.Builder(this)
            .setTitle("Sobre o Coletor Evelog")
            .setMessage("Versão $version\n\nSó Leitor de etiquetas padrão Jadlog.")
            .setPositiveButton("Fechar", null)
            .show()
    }

    private fun showDeleteBatchSelectionDialog() {
        if (batches.isEmpty()) {
            Toast.makeText(this, "Não há lotes para apagar.", Toast.LENGTH_SHORT).show()
            return
        }

        val candidates = batches.asReversed()
        val checked = BooleanArray(candidates.size)
        val labels = candidates.map { batch ->
            val state = if (batch.closedAt == null) "ABERTO" else "FECHADO"
            "$state · Lote Rua ${batch.name} · ${batch.entries.size} etiqueta(s)"
        }.toTypedArray()
        var selectAllButton: Button? = null

        val dialog = AlertDialog.Builder(this)
            .setTitle("Selecione os lotes para apagar")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
                selectAllButton?.text = if (checked.all { it }) "Desmarcar todos" else "Selecionar todos"
            }
            .setNeutralButton("Selecionar todos", null)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Continuar", null)
            .create()

        dialog.setOnShowListener {
            selectAllButton = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
            selectAllButton?.setOnClickListener {
                val selectEveryBatch = !checked.all { it }
                checked.indices.forEach { index ->
                    checked[index] = selectEveryBatch
                    dialog.listView.setItemChecked(index, selectEveryBatch)
                }
                selectAllButton?.text = if (selectEveryBatch) "Desmarcar todos" else "Selecionar todos"
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val selectedIds = candidates.filterIndexed { index, _ -> checked[index] }.map { it.id }
                if (selectedIds.isEmpty()) {
                    Toast.makeText(this, "Selecione pelo menos um lote.", Toast.LENGTH_SHORT).show()
                } else {
                    dialog.dismiss()
                    confirmDeleteBatches(selectedIds)
                }
            }
        }
        dialog.show()
    }

    private fun confirmDeleteBatches(selectedIds: List<String>) {
        val selectedBatches = batches.filter { it.id in selectedIds }
        if (selectedBatches.isEmpty()) return

        val allSelected = selectedBatches.size == batches.size
        val countLabels = selectedBatches.sumOf { it.entries.size }
        val names = selectedBatches.take(5).joinToString("\n") { "• Lote Rua ${it.name}" } +
            if (selectedBatches.size > 5) "\n• e mais ${selectedBatches.size - 5} lote(s)" else ""
        val title = if (allSelected) "Apagar todos os lotes?" else "Apagar ${selectedBatches.size} lote(s)?"
        val message = "$names\n\nSerão apagadas $countLabels etiqueta(s) deste aparelho. Essa ação não pode ser desfeita. Exporte os arquivos necessários antes. Digite a senha para confirmar."
        val passwordInput = EditText(this).apply {
            hint = "Senha"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setView(passwordInput)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Apagar selecionados", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (passwordInput.text.toString() != ADMIN_PASSWORD) {
                    passwordInput.error = "Senha incorreta"
                    passwordInput.text.clear()
                    return@setOnClickListener
                }
                batches.removeAll { it.id in selectedIds }
                expandedBatchIds.removeAll(selectedIds.toSet())
                saveBatches()
                dialog.dismiss()
                refreshScreen()
                status.text = "LOTES APAGADOS\n${selectedBatches.size} lote(s) removido(s)."
                status.setTextColor(BRAND_BLUE)
                status.background = rounded(INFO_BG, 16f)
            }
        }
        dialog.show()
    }

    private fun batchExportSummary(batch: LabelBatch): String {
        if (batch.entries.isEmpty()) return "SEM ETIQUETAS"
        return when {
            batch.exportedAt != null -> "EXPORTADO · ${formatDate(batch.exportedAt!!)}"
            batch.exportError != null -> "ERRO NA EXPORTAÇÃO"
            batch.closedAt == null -> "AGUARDANDO FECHAMENTO"
            else -> "EXPORTAÇÃO PENDENTE"
        }
    }

    @Deprecated("Activity result API kept compatible with older TC52 Android releases")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_FOLDER_REQUEST && resultCode == RESULT_OK && data?.data != null) {
            try {
                val uri = data.data!!
                val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                contentResolver.takePersistableUriPermission(uri, flags)
                storage.edit().putString(SAVE_FOLDER_URI_KEY, uri.toString()).apply()
                Toast.makeText(this, "Pasta configurada: ${folderDisplayName(uri)}", Toast.LENGTH_LONG).show()
                retryPendingExports()
            } catch (e: Exception) {
                Toast.makeText(this, "Não foi possível salvar essa pasta: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun formatDate(time: Long) = SimpleDateFormat("dd/MM HH:mm", Locale("pt", "BR")).format(Date(time))

    private fun formatManualCode(raw: String): String? {
        if (raw.any { it !in '0'..'9' && it != '$' }) return null
        val digits = raw.filter { it in '0'..'9' }
        if (digits.length > 26) return null
        return when {
            digits.length < 14 -> digits
            digits.length == 14 -> "$digits\$"
            else -> digits.substring(0, 14) + "\$" + digits.substring(14)
        }
    }

    private fun label(text: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun actionButton(text: String, fill: Int, textColor: Int, stroke: Int, action: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 16f
        isAllCaps = false
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(textColor)
        setPadding(dp(16), 0, dp(16), 0)
        background = rounded(fill, 15f, stroke)
        setOnClickListener { action() }
    }

    private fun rounded(fill: Int, radiusDp: Float, stroke: Int? = null) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radiusDp.toInt()).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private val SCREEN_BG = Color.rgb(244, 247, 251)
        private val BRAND_NAVY = Color.rgb(0, 48, 82)
        private val BRAND_BLUE = Color.rgb(0, 114, 192)
        private val BRAND_RED = Color.rgb(255, 27, 34)
        private val TEXT_PRIMARY = Color.rgb(24, 42, 58)
        private val TEXT_SECONDARY = Color.rgb(80, 99, 117)
        private val TEXT_MUTED = Color.rgb(125, 140, 153)
        private val BORDER = Color.rgb(220, 228, 236)
        private val SUCCESS_BG = Color.rgb(229, 246, 237)
        private val SUCCESS_TEXT = Color.rgb(29, 112, 66)
        private val ERROR_BG = Color.rgb(255, 235, 235)
        private val ERROR_TEXT = Color.rgb(160, 35, 35)
        private val INFO_BG = Color.rgb(231, 241, 250)
        private const val BATCHES_KEY = "batches_v1"
        private const val SCAN_ACTION = "br.com.evelog.coletor.SCAN"
        private const val DATAWEDGE_API_ACTION = "com.symbol.datawedge.api.ACTION"
        private const val DATAWEDGE_SET_CONFIG = "com.symbol.datawedge.api.SET_CONFIG"
        private const val PICK_FOLDER_REQUEST = 43
        private const val SAVE_FOLDER_URI_KEY = "save_folder_uri"
        private const val ADMIN_PASSWORD = "admin"
        private val DIGITS_ONLY_CODE = Regex("^[0-9]{26}$")
        private val VALID_CODE = Regex("^[0-9]{14}\\$[0-9]{12}$")
    }
}
