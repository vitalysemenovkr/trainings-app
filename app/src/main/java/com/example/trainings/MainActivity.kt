package com.example.trainings

import android.Manifest
import android.app.DatePickerDialog
import android.content.Intent
import android.content.ContentUris
import android.content.ContentValues
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.GridView
import android.widget.ImageButton
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButtonToggleGroup
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** type: "PAY" (оплата, count = число тренировок) или "USE" (списание одной тренировки). date: yyyy-MM-dd */
data class Event(val id: Long, val type: String, val date: String, val count: Int)

/** Ячейка календаря: day = null для пустых мест до начала месяца. count = сумма за день. */
data class DayCell(val day: Int?, val count: Int)

class MainActivity : AppCompatActivity() {

    private val events = mutableListOf<Event>()
    private lateinit var remainingView: TextView
    private lateinit var summaryView: TextView
    private lateinit var listView: ListView
    private val adapter = EventAdapter()

    // календарь
    private lateinit var listScreen: View
    private lateinit var calendarScreen: View
    private lateinit var calendarGrid: GridView
    private lateinit var monthTitle: TextView
    private lateinit var calendarSummary: TextView
    private val calAdapter = DayAdapter()
    private val monthView: Calendar = Calendar.getInstance()
    private var calendarMode = "USE"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        remainingView = findViewById(R.id.remaining)
        summaryView = findViewById(R.id.summary)
        listView = findViewById(R.id.list)
        listView.emptyView = findViewById(R.id.empty)
        listView.adapter = adapter

        findViewById<Button>(R.id.btnUse).setOnClickListener { onUseClicked() }
        findViewById<Button>(R.id.btnPay).setOnClickListener { onPayClicked() }
        findViewById<ImageButton>(R.id.btnMenu).setOnClickListener { showMenu(it) }

        listView.setOnItemLongClickListener { _, _, position, _ ->
            confirmDelete(adapter.getItem(position))
            true
        }

        // нижняя навигация: «Записи» / «Календарь»
        listScreen = findViewById(R.id.listScreen)
        calendarScreen = findViewById(R.id.calendarScreen)
        calendarGrid = findViewById(R.id.calendarGrid)
        calendarGrid.adapter = calAdapter
        monthTitle = findViewById(R.id.monthTitle)
        calendarSummary = findViewById(R.id.calendarSummary)

        findViewById<BottomNavigationView>(R.id.bottomNav).setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_list -> {
                    listScreen.visibility = View.VISIBLE
                    calendarScreen.visibility = View.GONE
                }
                R.id.nav_calendar -> {
                    listScreen.visibility = View.GONE
                    calendarScreen.visibility = View.VISIBLE
                    renderCalendar()
                }
            }
            true
        }

        findViewById<ImageButton>(R.id.btnPrevMonth).setOnClickListener { shiftMonth(-1) }
        findViewById<ImageButton>(R.id.btnNextMonth).setOnClickListener { shiftMonth(1) }

        findViewById<MaterialButtonToggleGroup>(R.id.modeToggle).addOnButtonCheckedListener { _, checkedId, _ ->
            calendarMode = if (checkedId == R.id.btnModePay) "PAY" else "USE"
            renderCalendar()
        }

        // тап по дню — список записей этого дня
        calendarGrid.setOnItemClickListener { _, _, position, _ ->
            showDayDialog(position)
        }

        load()
        refresh()
    }

    // ---------- действия ----------

    private fun onUseClicked() {
        pickDate { date ->
            val input = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                setText("2")
                setSelection(text.length)
            }
            AlertDialog.Builder(this)
                .setTitle("Тренировка ${formatDate(date)}")
                .setMessage("Сколько тренировок списать?")
                .setView(input)
                .setPositiveButton("Списать") { _, _ ->
                    val count = input.text.toString().toIntOrNull() ?: 0
                    if (count > 0) {
                        events.add(Event(System.currentTimeMillis(), "USE", date, count))
                        save()
                        refresh()
                    }
                }
                .setNegativeButton("Отмена", null)
                .show()
        }
    }

    private fun onPayClicked() {
        pickDate { date ->
            val input = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                setText("20")
                setSelection(text.length)
            }
            AlertDialog.Builder(this)
                .setTitle("Оплата от ${formatDate(date)}")
                .setMessage("Сколько тренировок оплачено?")
                .setView(input)
                .setPositiveButton("Добавить") { _, _ ->
                    val count = input.text.toString().toIntOrNull() ?: 0
                    if (count > 0) {
                        events.add(Event(System.currentTimeMillis(), "PAY", date, count))
                        save()
                        refresh()
                    }
                }
                .setNegativeButton("Отмена", null)
                .show()
        }
    }

    /** Меню «⋮» в правом верхнем углу: обновление, сохранение и восстановление истории. */
    private fun showMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.main_menu, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_update -> onUpdateClicked()
                R.id.menu_save -> saveToFile()
                R.id.menu_restore -> pickImportFile.launch("*/*")
            }
            true
        }
        popup.show()
    }

    /** «Сохранить в файл»: пишет актуальную историю в Загрузки/Trainings/history.json. */
    private fun saveToFile() {
        val arr = JSONArray()
        events.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("type", it.type)
                    .put("date", it.date)
                    .put("count", it.count)
            )
        }
        val json = arr.toString()
        getSharedPreferences("trainings", MODE_PRIVATE).edit().putString("events", json).apply()
        Toast.makeText(this, "Сохраняю файл…", Toast.LENGTH_SHORT).show()
        Thread {
            val ok = mirrorToDownloads(json)
            runOnUiThread {
                Toast.makeText(
                    this,
                    if (ok) "Сохранено: Загрузки/Trainings/history.json" else "Не удалось сохранить файл",
                    Toast.LENGTH_LONG
                ).show()
            }
        }.start()
    }

    private fun confirmDelete(event: Event) {
        AlertDialog.Builder(this)
            .setTitle("Удалить запись?")
            .setMessage("${describe(event)} — ${formatDate(event.date)}")
            .setPositiveButton("Удалить") { _, _ ->
                events.removeAll { it.id == event.id }
                save()
                refresh()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun pickDate(onPicked: (String) -> Unit) {
        val c = Calendar.getInstance()
        DatePickerDialog(
            this,
            { _, y, m, d -> onPicked(String.format("%04d-%02d-%02d", y, m + 1, d)) },
            c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    // ---------- данные ----------

    private fun refresh() {
        val paid = events.filter { it.type == "PAY" }.sumOf { it.count }
        val used = events.filter { it.type == "USE" }.sumOf { it.count }
        val remaining = paid - used

        remainingView.text = remaining.toString()
        remainingView.setTextColor(
            when {
                remaining <= 0 -> Color.parseColor("#D32F2F")
                remaining <= 3 -> Color.parseColor("#F57C00")
                else -> Color.parseColor("#388E3C")
            }
        )
        summaryView.text = "оплачено всего: $paid · списано: $used"

        // новые сверху; при равных датах — по порядку добавления (позднее выше)
        adapter.items = events.sortedWith(compareByDescending<Event> { it.date }.thenByDescending { it.id })
        adapter.notifyDataSetChanged()

        renderCalendar()
    }

    private fun load() {
        val prefs = getSharedPreferences("trainings", MODE_PRIVATE)
        if (prefs.contains("events")) {
            parseInto(prefs.getString("events", "[]")!!)
            return
        }
        // первый запуск (или переустановка): пробуем восстановить историю из «Загрузок» —
        // она сохраняется туда при каждом изменении и переживает удаление приложения
        if (!tryRestoreFromDownloads()) {
            seed()
        }
    }

    private fun parseInto(json: String) {
        val arr = JSONArray(json)
        events.clear()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            events.add(Event(o.getLong("id"), o.getString("type"), o.getString("date"), o.getInt("count")))
        }
    }

    /** Начальные данные при первом запуске (история из блокнота). После этого можно удалить записи долгим нажатием. */
    private fun seed() {
        var id = 1L
        fun pay(d: String, n: Int) { events.add(Event(id++, "PAY", d, n)) }
        fun use(d: String, n: Int) { events.add(Event(id++, "USE", d, n)) }

        pay("2026-10-02", 20)
        use("2026-10-02", 2)
        use("2026-10-05", 2)
        use("2026-10-07", 2)
        use("2026-10-09", 2)
        use("2026-10-12", 2)
        use("2026-10-14", 2)
        use("2026-10-16", 2)
        use("2026-10-19", 2)
        use("2026-10-21", 1)
        use("2026-10-23", 2)

        pay("2026-10-26", 19)
        use("2026-10-26", 2)
        use("2026-10-28", 2)
        use("2026-11-02", 2)
        use("2026-11-04", 2)
        use("2026-11-06", 1)
        use("2026-11-09", 2)
        use("2026-11-11", 1)
        use("2026-11-13", 1)
        use("2026-11-16", 2)
        use("2026-11-17", 2)
        save()
    }

    private fun save() {
        val arr = JSONArray()
        events.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("type", it.type)
                    .put("date", it.date)
                    .put("count", it.count)
            )
        }
        val json = arr.toString()
        getSharedPreferences("trainings", MODE_PRIVATE).edit().putString("events", json).apply()
        Thread { mirrorToDownloads(json) }.start()
    }

    // ---------- хранение истории в «Загрузках» (переживает удаление приложения) ----------

    private fun historyJsonName() = "history.json"
    private fun historyDir() = Environment.DIRECTORY_DOWNLOADS + "/Trainings"

    private fun canWriteLegacyStorage(): Boolean =
        checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    /** Копия истории в общей папке Download/Trainings. API 29+ — через MediaStore без разрешений. Возвращает успех. */
    private fun mirrorToDownloads(json: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val resolver = contentResolver
                val existing = findHistoryUri()
                if (existing != null) {
                    resolver.openOutputStream(existing, "wt")?.use { it.write(json.toByteArray()) } ?: return false
                } else {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, historyJsonName())
                        put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, historyDir())
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    val newUri = resolver.insert(collection, values) ?: return false
                    resolver.openOutputStream(newUri)?.use { it.write(json.toByteArray()) } ?: return false
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(newUri, values, null, null)
                }
                true
            } else {
                // API 26–28: прямой файл (нужно разрешение WRITE_EXTERNAL_STORAGE)
                if (!canWriteLegacyStorage()) return false
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Trainings")
                if (!dir.exists()) dir.mkdirs()
                File(dir, historyJsonName()).writeText(json)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun findHistoryUri(): Uri? {
        if (Build.VERSION.SDK_INT < 29) return null
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val sel = "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?"
        contentResolver.query(
            collection, arrayOf(MediaStore.MediaColumns._ID), sel,
            arrayOf(historyDir(), historyJsonName()), null
        )?.use { c ->
            if (c.moveToFirst()) return ContentUris.withAppendedId(collection, c.getLong(0))
        }
        return null
    }

    /** Восстановление при переустановке: файл в «Загрузках» принадлежит этому же package name. */
    private fun tryRestoreFromDownloads(): Boolean {
        return try {
            val json: String? = if (Build.VERSION.SDK_INT >= 29) {
                val uri = findHistoryUri() ?: return false
                contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            } else {
                if (!canWriteLegacyStorage()) return false
                val f = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "Trainings/${historyJsonName()}"
                )
                if (f.exists()) f.readText() else null
            }
            if (json != null && json.trimStart().startsWith("[")) {
                parseInto(json)
                getSharedPreferences("trainings", MODE_PRIVATE).edit().putString("events", json).apply()
                true
            } else false
        } catch (_: Exception) {
            false
        }
    }

    /** Ручное восстановление через выбор файла (если автопоиск не сработал). */
    private val pickImportFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) importFromUri(uri)
    }

    private fun importFromUri(uri: Uri) {
        try {
            val json = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            if (json == null || !json.trimStart().startsWith("[")) {
                Toast.makeText(this, "Это не файл истории", Toast.LENGTH_SHORT).show()
                return
            }
            parseInto(json)
            save()
            refresh()
            Toast.makeText(this, "История восстановлена", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, "Не удалось прочитать файл", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- обновление из репозитория ----------

    private fun onUpdateClicked() {
        Toast.makeText(this, "Проверяю обновления…", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val conn = URL("https://api.github.com/repos/vitalysemenovkr/trainings-app/releases/latest").openConnection() as HttpURLConnection
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val obj = JSONObject(body)
                val latest = obj.getString("tag_name").removePrefix("v").toIntOrNull() ?: 0
                val apkUrl = obj.getJSONArray("assets").getJSONObject(0).getString("browser_download_url")
                val current = packageManager.getPackageInfo(packageName, 0).longVersionCode
                runOnUiThread {
                    if (latest > current) {
                        AlertDialog.Builder(this)
                            .setTitle("Доступно обновление")
                            .setMessage("Версия v$latest (у вас v$current). Скачать и установить?")
                            .setPositiveButton("Обновить") { _, _ -> downloadUpdate(apkUrl) }
                            .setNegativeButton("Позже", null)
                            .show()
                    } else {
                        Toast.makeText(this, "У вас последняя версия (v$current)", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (_: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Не удалось проверить обновления (нет интернета?)", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun downloadUpdate(url: String) {
        Toast.makeText(this, "Скачиваю обновление…", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val dir = File(cacheDir, "updates").apply { mkdirs() }
                val file = File(dir, "update.apk")
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 60000
                conn.inputStream.use { input ->
                    FileOutputStream(file).use { output -> input.copyTo(output, 8192) }
                }
                runOnUiThread { installUpdate(file) }
            } catch (_: Exception) {
                runOnUiThread { Toast.makeText(this, "Ошибка скачивания", Toast.LENGTH_SHORT).show() }
            }
        }.start()
    }

    private fun installUpdate(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            Toast.makeText(
                this,
                "Разрешите установку для «Тренировки», затем нажмите «Проверить обновления» ещё раз",
                Toast.LENGTH_LONG
            ).show()
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
        } else {
            startActivity(intent)
        }
    }

    // ---------- календарь статистики ----------

    private fun shiftMonth(delta: Int) {
        monthView.add(Calendar.MONTH, delta)
        renderCalendar()
    }

    /** Перестраивает сетку текущего месяца и сводку. Вызывается при смене месяца/режима и при refresh(). */
    private fun renderCalendar() {
        val y = monthView.get(Calendar.YEAR)
        val m = monthView.get(Calendar.MONTH) // 0-based

        val title = SimpleDateFormat("LLLL yyyy", Locale("ru")).format(monthView.time)
        monthTitle.text = title.replaceFirstChar { it.uppercaseChar() }

        // суммы по дням для выбранного режима
        val prefix = String.format("%04d-%02d", y, m + 1)
        val byDay = HashMap<String, Int>()
        events.filter { it.type == calendarMode && it.date.startsWith(prefix) }
            .forEach { byDay[it.date] = (byDay[it.date] ?: 0) + it.count }

        // пустые ячейки до первого дня (неделя с понедельника) + дни месяца, добитые до полных недель
        val first = (monthView.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, 1) }
        val firstDow = (first.get(Calendar.DAY_OF_WEEK) + 5) % 7 // Пн=0..Вс=6
        val daysInMonth = monthView.getActualMaximum(Calendar.DAY_OF_MONTH)
        val total = ((firstDow + daysInMonth + 6) / 7) * 7

        val cells = ArrayList<DayCell>(total)
        repeat(firstDow) { cells.add(DayCell(null, 0)) }
        for (d in 1..daysInMonth) {
            val iso = String.format("%04d-%02d-%02d", y, m + 1, d)
            cells.add(DayCell(d, byDay[iso] ?: 0))
        }
        while (cells.size < total) cells.add(DayCell(null, 0))
        calAdapter.cells = cells
        calAdapter.notifyDataSetChanged()

        // сводка за месяц по обоим типам
        val uses = events.filter { it.type == "USE" && it.date.startsWith(prefix) }.sumOf { it.count }
        val pays = events.filter { it.type == "PAY" && it.date.startsWith(prefix) }.sumOf { it.count }
        val monthName = SimpleDateFormat("LLLL", Locale("ru")).format(monthView.time)
        calendarSummary.text = "За $monthName: тренировок −$uses · оплат +$pays"
    }

    private fun showDayDialog(position: Int) {
        val cell = calAdapter.cells.getOrNull(position) ?: return
        val d = cell.day ?: return
        val iso = String.format(
            "%04d-%02d-%02d",
            monthView.get(Calendar.YEAR), monthView.get(Calendar.MONTH) + 1, d
        )
        val dayEvents = events.filter { it.date == iso }.sortedBy { it.id }
        if (dayEvents.isEmpty()) return
        val msg = dayEvents.joinToString("\n") { describe(it) }
        AlertDialog.Builder(this)
            .setTitle(formatDate(iso))
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .show()
    }

    private inner class DayAdapter : BaseAdapter() {
        var cells: List<DayCell> = emptyList()

        override fun getCount() = cells.size
        override fun getItem(position: Int) = cells[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView
                ?: LayoutInflater.from(this@MainActivity).inflate(R.layout.item_calendar_day, parent, false)
            val tv = v.findViewById<TextView>(R.id.dayCell)
            // цвета темы сохраняем в tag: GridView переиспользует ячейки
            val defaultColors = v.tag as? ColorStateList
                ?: tv.textColors.also { v.tag = it }
            val cell = cells[position]

            if (cell.day == null) {
                tv.text = ""
                tv.background = null
            } else {
                tv.text = cell.day.toString()
                val today = Calendar.getInstance()
                val isToday =
                    today.get(Calendar.YEAR) == monthView.get(Calendar.YEAR) &&
                        today.get(Calendar.MONTH) == monthView.get(Calendar.MONTH) &&
                        today.get(Calendar.DAY_OF_MONTH) == cell.day
                when {
                    cell.count > 0 && calendarMode == "USE" -> {
                        tv.background = ContextCompat.getDrawable(this@MainActivity, R.drawable.circle_workout)
                        tv.setTextColor(Color.WHITE)
                    }
                    cell.count > 0 -> {
                        tv.background = ContextCompat.getDrawable(this@MainActivity, R.drawable.circle_pay)
                        tv.setTextColor(Color.WHITE)
                    }
                    isToday -> {
                        tv.background = ContextCompat.getDrawable(this@MainActivity, R.drawable.circle_today)
                        tv.setTextColor(defaultColors)
                    }
                    else -> {
                        tv.background = null
                        tv.setTextColor(defaultColors)
                    }
                }
            }
            return v
        }
    }

    // ---------- отображение ----------

    private fun formatDate(iso: String): String {
        val p = iso.split("-")
        return if (p.size == 3) "${p[2]}.${p[1]}.${p[0]}" else iso
    }

    private fun describe(e: Event) =
        if (e.type == "PAY") "Оплата: +${e.count}" else "Тренировки: −${e.count}"

    private inner class EventAdapter : BaseAdapter() {
        var items: List<Event> = emptyList()

        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = items[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView
                ?: LayoutInflater.from(this@MainActivity).inflate(R.layout.item_event, parent, false)
            val e = items[position]
            val title = v.findViewById<TextView>(R.id.title)
            title.text = describe(e)
            // исходные цвета темы сохраняем в tag: ListView переиспользует view,
            // и без этого после зелёной строки оплаты цвет «прилипал» к строкам списания
            val defaultColors = v.tag as? ColorStateList
                ?: title.textColors.also { v.tag = it }
            title.setTextColor(
                if (e.type == "PAY") ColorStateList.valueOf(Color.parseColor("#388E3C"))
                else defaultColors
            )
            v.findViewById<TextView>(R.id.date).text = formatDate(e.date)
            return v
        }
    }
}
