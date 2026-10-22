package com.kalitemall.deposayim

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Compatible with the previously supplied v0.4/v0.5 Apps Script HMAC API. */
class SheetsSync(private val context: Context, private val store: ScanStore, private val notify: (String)->Unit) {
    private val prefs = context.getSharedPreferences("km_native_sync",Context.MODE_PRIVATE)
    private val executor=Executors.newSingleThreadExecutor()
    private val main=Handler(Looper.getMainLooper())
    private var busy=false // only accessed by single-thread executor
    val url get() = prefs.getString("url","") ?: ""
    val secret get() = prefs.getString("secret","") ?: ""
    fun hasSettings() = valid(url,secret)
    private fun valid(u:String,s:String) =
        Regex("^https://script\\.google\\.com/macros/s/[A-Za-z0-9_-]+/exec/?$").matches(u.trim()) &&
        Regex("^[a-fA-F0-9]{64}$").matches(s.trim())
    fun configure(u:String,s:String, callback:(Boolean,String)->Unit) {
        val endpoint=u.trim().trimEnd('/')
        val key=s.trim()
        if(!valid(endpoint,key)) { callback(false,"Apps Script /exec adresi veya gizli anahtar geçersiz.");return }
        executor.execute {
            try {
                val good=ping(endpoint,key)
                main.post {
                    if(good) {
                        prefs.edit().putString("url",endpoint).putString("secret",key).apply()
                        callback(true,"Bağlantı doğrulandı, bekleyen kayıtlar gönderilecek.")
                        sync()
                    } else callback(false,"Apps Script doğrulaması başarısız; dağıtım, Herkes ve anahtarı kontrol et.")
                }
            } catch (e:Exception) { main.post { callback(false,"Bağlantı hatası: ${e.message ?: "Bilinmiyor"}") } }
        }
    }
    private fun signature(message:String, key:String):String {
        val mac=Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8),"HmacSHA256"))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
    private fun encode(v:String)=URLEncoder.encode(v,"UTF-8")
    private fun post(endpoint:String, body:String):String {
        val connection=(URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod="POST"; connectTimeout=12000; readTimeout=15000
            doOutput=true; instanceFollowRedirects=true; useCaches=false
            setRequestProperty("Content-Type","application/x-www-form-urlencoded; charset=UTF-8")
            setRequestProperty("Accept","application/json")
        }
        try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code=connection.responseCode
            if(code !in 200..299) throw IllegalStateException("HTTP $code")
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally { connection.disconnect() }
    }
    private fun get(url:String):String {
        val c=(URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout=12000;readTimeout=15000;instanceFollowRedirects=true
        }
        try {
            if(c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally { c.disconnect() }
    }
    private fun ping(endpoint:String,key:String):Boolean {
        val id=UUID.randomUUID().toString()
        val ts=System.currentTimeMillis().toString()
        val sig=signature("KMSTATUSv04\n$ts\n$id",key)
        val cb="kmcb_check12345678"
        val data=get("$endpoint?mode=status&ids=${encode(id)}&ts=$ts&sig=$sig&callback=$cb")
        if(!data.startsWith("$cb(") || !data.endsWith(");")) return false
        val json=JSONObject(data.substring(cb.length+1,data.length-2))
        return json.optBoolean("ok",false)
    }
    fun sync() {
        executor.execute {
            if(busy) return@execute
            busy=true
            try {
                if(!hasSettings()) { status("Google Sheets ayarlı değil. Yerel kayıt güvenli.");return@execute }
                var rounds=0
                while(rounds++ < 20) {
                    val list=store.pending(15)
                    if(list.isEmpty()) { status("✓ Sheets eşitlendi · Bekleyen 0");return@execute }
                    val json=JSONArray()
                    list.forEach { json.put(it.toJson()) }
                    val payload=json.toString()
                    val ts=System.currentTimeMillis().toString()
                    val sig=signature("KMv04\n$ts\n$payload",secret)
                    val form="mode=save&ts=${encode(ts)}&sig=${encode(sig)}&data=${encode(payload)}"
                    val res=JSONObject(post(url,form))
                    if(!res.optBoolean("ok",false)) throw IllegalStateException(res.optString("error","Sheets reddetti"))
                    // The authenticated server commits the full validated batch or throws.
                    store.markSynced(list.map { it.id })
                    status("✓ Sheets · Bekleyen ${store.pendingCount()}")
                }
            } catch (e:Exception) { status("Sheets gönderilemedi · ${store.pendingCount()} bekliyor (${e.message})") }
            finally { busy=false }
        }
    }
    private fun status(text:String) = main.post { notify(text) }
    fun close() = executor.shutdown()
}
