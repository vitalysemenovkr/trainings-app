package com.example.trainings

import android.app.DatePickerDialog
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
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
        if (!prefs.contains("events")) {
            seed()
            return
        }
        val arr = JSONArray(prefs.getString("events", "[]"))
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
        getSharedPreferences("trainings", MODE_PRIVATE).edit().putString("events", arr.toString()).apply()
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
            title.setTextColor(if (e.type == "PAY") Color.parseColor("#388E3C") else title.textColors.defaultColor)
            v.findViewById<TextView>(R.id.date).text = formatDate(e.date)
            return v
        }
    }
}
