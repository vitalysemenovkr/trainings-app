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
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar

/** type: "PAY" (оплата, count = число тренировок) или "USE" (списание одной тренировки). date: yyyy-MM-dd */
data class Event(val id: Long, val type: String, val date: String, val count: Int)

class MainActivity : AppCompatActivity() {

    private val events = mutableListOf<Event>()
    private lateinit var remainingView: TextView
    private lateinit var summaryView: TextView
    private lateinit var listView: ListView
    private val adapter = EventAdapter()

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
        findViewById<Button>(R.id.btnUpdate).setOnClickListener { onUpdateClicked() }
        findViewById<Button>(R.id.btnImport).setOnClickListener { pickImportFile.launch("*/*") }

        listView.setOnItemLongClickListener { _, _, position, _ ->
            confirmDelete(adapter.getItem(position))
            true
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

    /** Копия истории в общей папке Download/Trainings. API 29+ — через MediaStore без разрешений. */
    private fun mirrorToDownloads(json: String) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val resolver = contentResolver
                val existing = findHistoryUri()
                if (existing != null) {
                    resolver.openOutputStream(existing, "wt")?.use { it.write(json.toByteArray()) }
                } else {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, historyJsonName())
                        put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, historyDir())
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    val newUri = resolver.insert(collection, values) ?: return
                    resolver.openOutputStream(newUri)?.use { it.write(json.toByteArray()) }
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(newUri, values, null, null)
                }
            } else {
                // API 26–28: прямой файл (нужно разрешение WRITE_EXTERNAL_STORAGE)
                if (!canWriteLegacyStorage()) return
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Trainings")
                if (!dir.exists()) dir.mkdirs()
                File(dir, historyJsonName()).writeText(json)
            }
        } catch (_: Exception) {
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
