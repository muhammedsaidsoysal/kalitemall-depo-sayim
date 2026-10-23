package com.kalitemall.deposayim

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class CountEvent(
    val id: String, val occurred: Long, val sku: String, val size: String,
    val box: String, val qty: Int, val source: String, val confidence: Float,
    val rawText: String, val undoOf: String, val synced: Boolean
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("sku", sku); put("size", size); put("color", "")
        put("box", box); put("qty", qty); put("rawText", rawText); put("undoOf", undoOf)
    }
}

/** Device-local journal; idempotency IDs survive app restarts and failed uploads. */
class ScanStore(context: Context) : SQLiteOpenHelper(context, "km_native_scan.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE events (
            id TEXT PRIMARY KEY, occurred INTEGER NOT NULL, sku TEXT NOT NULL,
            size TEXT NOT NULL, box TEXT NOT NULL, qty INTEGER NOT NULL,
            source TEXT NOT NULL, confidence REAL NOT NULL, raw_text TEXT NOT NULL,
            undo_of TEXT NOT NULL DEFAULT '', synced INTEGER NOT NULL DEFAULT 0
        )""")
        db.execSQL("CREATE INDEX idx_events_sync ON events(synced, occurred)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        throw IllegalStateException("Database migration required; never destroy inventory data")
    }
    fun add(sku: String, size: String, box: String, confidence: Float, raw: String, source: String = "OCR"): CountEvent {
        val item = CountEvent(UUID.randomUUID().toString(), System.currentTimeMillis(), sku, size,
            box, 1, source, confidence, raw.take(800), "", false)
        insert(item)
        return item
    }
    private fun insert(e: CountEvent) {
        val v = ContentValues().apply {
            put("id",e.id); put("occurred",e.occurred); put("sku",e.sku)
            put("size",e.size); put("box",e.box); put("qty",e.qty)
            put("source",e.source); put("confidence",e.confidence)
            put("raw_text",e.rawText); put("undo_of",e.undoOf); put("synced",if(e.synced) 1 else 0)
        }
        writableDatabase.insertOrThrow("events",null,v)
    }
    private fun row(c: android.database.Cursor) = CountEvent(
        c.getString(0),c.getLong(1),c.getString(2),c.getString(3),c.getString(4),
        c.getInt(5),c.getString(6),c.getFloat(7),c.getString(8),c.getString(9),c.getInt(10)!=0
    )
    private val cols = "id,occurred,sku,size,box,qty,source,confidence,raw_text,undo_of,synced"
    fun pending(limit: Int = 15): List<CountEvent> = readableDatabase.rawQuery(
        "SELECT $cols FROM events WHERE synced=0 ORDER BY occurred ASC LIMIT ?", arrayOf(limit.toString())
    ).use { c -> buildList { while(c.moveToNext()) add(row(c)) } }
    fun pendingCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM events WHERE synced=0",null)
        .use { if(it.moveToNext()) it.getInt(0) else 0 }
    fun total(): Int = readableDatabase.rawQuery("SELECT COALESCE(SUM(qty),0) FROM events",null)
        .use { if(it.moveToNext()) it.getInt(0) else 0 }
    fun markSynced(ids: List<String>) {
        writableDatabase.beginTransaction()
        try {
            ids.forEach { id ->
                val v=ContentValues().apply { put("synced",1) }
                writableDatabase.update("events",v,"id=?",arrayOf(id))
            }
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
    }
    fun undoLast(): CountEvent? {
        val original = readableDatabase.rawQuery(
            """SELECT $cols FROM events e WHERE e.qty=1 AND NOT EXISTS
            (SELECT 1 FROM events u WHERE u.undo_of=e.id)
            ORDER BY e.occurred DESC LIMIT 1""", null
        ).use { c -> if(c.moveToFirst()) row(c) else null } ?: return null
        val undo = original.copy(id=UUID.randomUUID().toString(),occurred=System.currentTimeMillis(),
            qty=-1, source="UNDO", confidence=1f,undoOf=original.id,synced=false)
        insert(undo)
        return undo
    }
    fun csv(): String {
        fun q(s:String) = "\""+s.replace("\"","\"\"")+"\""
        val sb=StringBuilder("\uFEFFTarih;IslemID;Kutu;StokKodu;Beden;Adet;Kaynak;OCRGuveni;HamOCR;GeriAlinanIslemID;Sheets\n")
        val dateFormat=SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale("tr","TR"))
        readableDatabase.rawQuery("SELECT $cols FROM events ORDER BY occurred",null).use { c ->
            while(c.moveToNext()) {
                val e=row(c)
                sb.append(listOf(dateFormat.format(Date(e.occurred)),e.id,e.box,e.sku,e.size,e.qty.toString(),
                    e.source,e.confidence.toString(),e.rawText,e.undoOf,if(e.synced)"EVET" else "HAYIR")
                    .joinToString(";") { q(it) }).append('\n')
            }
        }
        return sb.toString()
    }
}
