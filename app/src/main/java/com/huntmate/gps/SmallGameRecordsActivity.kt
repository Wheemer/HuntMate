package com.huntmate.gps

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class SmallGameRecordsActivity : AppCompatActivity() {
    private data class Record(val id: String, val species: String, val date: String, val count: Int, val area: String, val notes: String)
    private val prefs by lazy { getSharedPreferences("huntmate_small_game_records", MODE_PRIVATE) }
    private val records = mutableListOf<Record>()
    private var editingId: String? = null
    private lateinit var species: Spinner
    private lateinit var date: EditText
    private lateinit var count: EditText
    private lateinit var area: EditText
    private lateinit var notes: EditText
    private lateinit var search: EditText
    private lateinit var list: LinearLayout
    private lateinit var heading: TextView
    private lateinit var save: Button
    private lateinit var cancel: Button

    private val exportCsv = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) runCatching {
            contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(csv()) } ?: error("Could not open selected file")
        }.onSuccess { toast("CSV exported.") }.onFailure { Toast.makeText(this, "Could not export the CSV.", Toast.LENGTH_LONG).show() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); setContentView(R.layout.activity_small_game_records)
        species = findViewById(R.id.recordSpecies); date = findViewById(R.id.recordDate); count = findViewById(R.id.recordCount)
        area = findViewById(R.id.recordArea); notes = findViewById(R.id.recordNotes); search = findViewById(R.id.recordSearch)
        list = findViewById(R.id.recordsList); heading = findViewById(R.id.recordFormHeading)
        save = findViewById(R.id.saveRecordButton); cancel = findViewById(R.id.cancelRecordEditButton)
        species.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, arrayOf("Choose species", "Snowshoe hare", "Ruffed grouse", "Spruce grouse", "Willow ptarmigan", "Rock ptarmigan", "Porcupine", "Coyote", "Fox", "Other"))
        date.setText(today()); date.setOnClickListener { pickDate() }
        findViewById<Button>(R.id.closeRecordsButton).setOnClickListener { finish() }
        save.setOnClickListener { saveRecord() }; cancel.setOnClickListener { resetForm() }
        findViewById<Button>(R.id.exportRecordsButton).setOnClickListener { if (records.isEmpty()) toast("No records to export yet.") else exportCsv.launch("huntmate-small-game-records.csv") }
        findViewById<Button>(R.id.deleteAllRecordsButton).setOnClickListener { confirmDeleteAll() }
        search.addTextChangedListener(SimpleTextWatcher { render() }); load(); render()
    }

    private fun saveRecord() {
        val chosen = species.selectedItem?.toString().orEmpty(); val harvested = count.text.toString().toIntOrNull()
        if (chosen == "Choose species" || chosen.isBlank()) { toast("Choose a species."); return }
        if (date.text.isBlank()) { toast("Choose the date hunted."); return }
        if (harvested == null || harvested !in 1..999) { toast("Enter a number from 1 to 999."); return }
        val record = Record(editingId ?: System.currentTimeMillis().toString(), chosen, date.text.toString(), harvested, area.text.toString().trim(), notes.text.toString().trim())
        val index = records.indexOfFirst { it.id == record.id }
        if (index >= 0) records[index] = record else records += record
        persist(); resetForm(); render()
    }

    private fun edit(record: Record) {
        editingId = record.id
        species.setSelection((0 until species.count).firstOrNull { species.getItemAtPosition(it) == record.species } ?: 0)
        date.setText(record.date); count.setText(record.count.toString()); area.setText(record.area); notes.setText(record.notes)
        heading.text = "Edit record"; save.text = "Update record"; cancel.visibility = View.VISIBLE
    }

    private fun delete(record: Record) {
        AlertDialog.Builder(this).setTitle("Delete record?").setMessage("Delete this record?").setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ ->
            records.removeAll { it.id == record.id }; persist(); if (editingId == record.id) resetForm(); render()
        }.show()
    }

    private fun confirmDeleteAll() {
        if (records.isEmpty()) return
        AlertDialog.Builder(this).setTitle("Delete all records?").setMessage("This cannot be undone. Export a CSV backup first if you need one.").setNegativeButton("Cancel", null).setPositiveButton("Delete all") { _, _ ->
            records.clear(); persist(); resetForm(); render()
        }.show()
    }

    private fun resetForm() {
        editingId = null; species.setSelection(0); date.setText(today()); count.setText("1"); area.text?.clear(); notes.text?.clear()
        heading.text = "Add a record"; save.text = "Save record"; cancel.visibility = View.GONE
    }

    private fun render() {
        val query = search.text.toString().trim().lowercase()
        val shown = records.filter { listOf(it.species, it.date, it.area, it.notes).joinToString(" ").lowercase().contains(query) }.sortedByDescending { it.date }
        findViewById<TextView>(R.id.totalHarvestedValue).text = records.sumOf { it.count }.toString()
        findViewById<TextView>(R.id.totalRecordsValue).text = records.size.toString()
        findViewById<TextView>(R.id.totalSpeciesValue).text = records.map { it.species }.toSet().size.toString()
        list.removeAllViews()
        if (shown.isEmpty()) { TextView(this).apply { text = if (records.isEmpty()) "No records yet. Add your first harvest above." else "No matching records."; setPadding(8, 20, 8, 20); list.addView(this) }; return }
        shown.forEach { record ->
            val row = layoutInflater.inflate(R.layout.item_small_game_record, list, false)
            row.findViewById<TextView>(R.id.itemSpecies).text = record.species; row.findViewById<TextView>(R.id.itemDate).text = record.date
            row.findViewById<TextView>(R.id.itemCount).text = "x" + record.count
            row.findViewById<TextView>(R.id.itemDetails).text = listOfNotNull(record.area.takeIf { it.isNotBlank() }?.let { "Area: " + it }, record.notes.takeIf { it.isNotBlank() }).joinToString(" - ").ifBlank { "No additional notes" }
            row.findViewById<Button>(R.id.editRecordButton).setOnClickListener { edit(record) }; row.findViewById<Button>(R.id.deleteRecordButton).setOnClickListener { delete(record) }; list.addView(row)
        }
    }

    private fun load() = runCatching {
        val array = JSONArray(prefs.getString("records", "[]") ?: "[]")
        for (i in 0 until array.length()) { val item = array.getJSONObject(i); records += Record(item.getString("id"), item.getString("species"), item.getString("date"), item.getInt("count"), item.optString("area"), item.optString("notes")) }
    }

    private fun persist() {
        val array = JSONArray()
        records.forEach { record -> array.put(JSONObject().apply { put("id", record.id); put("species", record.species); put("date", record.date); put("count", record.count); put("area", record.area); put("notes", record.notes) }) }
        prefs.edit().putString("records", array.toString()).apply()
    }

    private fun csv(): String = buildString {
        append("\uFEFFdate,species,count,area,notes\r\n")
        records.sortedByDescending { it.date }.forEach { record -> append(listOf(record.date, record.species, record.count.toString(), record.area, record.notes).joinToString(",") { value -> quote(value) }); append("\r\n") }
    }
    private fun quote(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""
    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.CANADA).format(Calendar.getInstance().time)
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun pickDate() {
        val parts = date.text.toString().split("-").mapNotNull { it.toIntOrNull() }; val calendar = Calendar.getInstance()
        DatePickerDialog(this, { _, year, month, day -> date.setText("%04d-%02d-%02d".format(year, month + 1, day)) }, parts.getOrElse(0) { calendar.get(Calendar.YEAR) }, parts.getOrElse(1) { calendar.get(Calendar.MONTH) + 1 } - 1, parts.getOrElse(2) { calendar.get(Calendar.DAY_OF_MONTH) }).show()
    }
}