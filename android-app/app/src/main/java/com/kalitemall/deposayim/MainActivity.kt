package com.kalitemall.deposayim

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.content.Context
import android.util.Size
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.kalitemall.deposayim.databinding.ActivityMainBinding
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var ui: ActivityMainBinding
    private lateinit var store: ScanStore
    private lateinit var sheets: SheetsSync
    private val recognizer=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val analyzerExecutor=Executors.newSingleThreadExecutor()
    private val counter=AutoCounter()
    private var provider:ProcessCameraProvider?=null
    private var cameraOn=false
    private var boundCamera: Camera?=null
    private var torchEnabled=false
    private var tone:ToneGenerator?=null
    private val timer=Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("km_ui", MODE_PRIVATE) }
    private val exportCsv=registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if(uri!=null) try {
            contentResolver.openOutputStream(uri)?.use { it.write(store.csv().toByteArray(Charsets.UTF_8)) }
            toast("CSV yedeği oluşturuldu")
        } catch(e:Exception) { toast("CSV hatası: ${e.message}") }
    }
    private val cameraPermission=registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if(granted) startCamera() else toast("Kamera izni gerekli")
    }
    private val retry=object:Runnable {
        override fun run() {
            if(::sheets.isInitialized) sheets.sync()
            if(::store.isInitialized) updateTotals()
            timer.postDelayed(this,7000)
        }
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        ui=ActivityMainBinding.inflate(layoutInflater)
        setContentView(ui.root)
        store=ScanStore(this)
        sheets=SheetsSync(this,store) { msg -> ui.syncStatus.text=msg;updateTotals() }
        tone=try { ToneGenerator(AudioManager.STREAM_NOTIFICATION,85) } catch (_:Exception) { null }
        ui.boxInput.setText(prefs.getString("box","KUTU-1"))
        ui.scriptUrlInput.setText(sheets.url)
        ui.secretInput.setText(sheets.secret)
        ui.cameraButton.setOnClickListener { if(cameraOn) stopCamera() else requestCamera() }
        ui.torchButton.setOnClickListener {
            val camera=boundCamera
            if(camera == null) { toast("Önce kamera açılmalı");return@setOnClickListener }
            if(!camera.cameraInfo.hasFlashUnit()) { toast("Bu kamera fener desteklemiyor");return@setOnClickListener }
            torchEnabled = !torchEnabled
            camera.cameraControl.enableTorch(torchEnabled)
            ui.torchButton.text=if(torchEnabled) "Fener ✓" else "Fener"
        }
        ui.undoButton.setOnClickListener {
            val undo=store.undoLast()
            if(undo==null) toast("Geri alınacak işlem yok")
            else {
                counter.reset()
                ui.lastScanText.text="Geri alındı: ${undo.sku} · ${undo.size} (-1)"
                updateTotals();sheets.sync();toast("Son kayıt geri alındı")
            }
        }
        ui.exportButton.setOnClickListener { exportCsv.launch("KaliteMall_Sayim_${System.currentTimeMillis()}.csv") }
        ui.connectButton.setOnClickListener {
            ui.syncStatus.text="Bağlantı test ediliyor…"
            sheets.configure(ui.scriptUrlInput.text.toString(),ui.secretInput.text.toString()) { ok,msg ->
                ui.syncStatus.text=msg
                toast(msg)
                if(ok) updateTotals()
            }
        }
        ui.syncNowButton.setOnClickListener { sheets.sync() }
        ui.syncStatus.text=if(sheets.hasSettings()) "Google Sheets ayarlı; bekleyenler gönderilecek" else "Yalnızca cihaz kaydı (Sheets ayarlanmadı)"
        updateTotals()
        timer.postDelayed(retry,1500)
        requestCamera()
    }
    private fun requestCamera() {
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)
            startCamera()
        else cameraPermission.launch(Manifest.permission.CAMERA)
    }
    @Suppress("DEPRECATION")
    private fun startCamera() {
        val box=ui.boxInput.text.toString().trim()
        if(box.isBlank()) { toast("Önce kutu/raf gir");return }
        prefs.edit().putString("box",box).apply()
        ui.cameraStatus.text="Kamera hazırlanıyor…"
        val future=ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val p=future.get()
                provider=p
                val preview=Preview.Builder().build().also { it.setSurfaceProvider(ui.cameraPreview.surfaceProvider) }
                // Limit camera data volume; never run OCR on 48 MP still photos.
                val analysis=ImageAnalysis.Builder()
                    .setTargetResolution(Size(1280,720))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analyzerExecutor) { imageProxy ->
                    val mediaImage=imageProxy.image
                    if(mediaImage==null) { imageProxy.close();return@setAnalyzer }
                    val rotation=imageProxy.imageInfo.rotationDegrees
                    val width=if(rotation==90 || rotation==270) imageProxy.height else imageProxy.width
                    val height=if(rotation==90 || rotation==270) imageProxy.width else imageProxy.height
                    val input=InputImage.fromMediaImage(mediaImage,rotation)
                    val started=SystemClock.elapsedRealtime()
                    recognizer.process(input)
                        .addOnSuccessListener { text ->
                            val lines=buildList {
                                text.textBlocks.forEachIndexed { blockIndex,block ->
                                    block.lines.forEach { line ->
                                        val r=line.boundingBox
                                        if(r!=null) add(OcrLine(line.text,line.confidence,
                                            r.exactCenterX()/width.toFloat(),r.exactCenterY()/height.toFloat(),blockIndex))
                                    }
                                }
                            }
                            val found=ScanRules.parse(lines)
                            // Called on UI thread by ML Kit task listener.
                            onReading(found,SystemClock.elapsedRealtime()-started,
                                lines.none { it.centerX in 0.08f..0.92f && it.centerY in 0.12f..0.88f })
                        }
                        .addOnFailureListener { e -> ui.cameraStatus.text="OCR hatası: ${e.message}" }
                        .addOnCompleteListener { imageProxy.close() }
                }
                p.unbindAll()
                boundCamera=p.bindToLifecycle(this,CameraSelector.DEFAULT_BACK_CAMERA,preview,analysis)
                cameraOn=true
                counter.reset()
                ui.cameraButton.text="Kamerayı durdur"
                ui.cameraStatus.text="Canlı tarama açık · etiketi çerçeveye getir"
            } catch(e:Exception) { ui.cameraStatus.text="Kamera açılamadı: ${e.message}";cameraOn=false }
        },ContextCompat.getMainExecutor(this))
    }
    private fun stopCamera() {
        boundCamera?.cameraControl?.enableTorch(false)
        boundCamera=null;torchEnabled=false
        ui.torchButton.text="Fener"
        provider?.unbindAll();cameraOn=false;counter.reset()
        ui.cameraButton.text="Kamerayı aç"
        ui.cameraStatus.text="Kamera durduruldu"
    }
    @Suppress("DEPRECATION")
    private fun onReading(candidate:ScanCandidate?, durationMs:Long, blankScene:Boolean) {
        if(!cameraOn) return
        val box=ui.boxInput.text.toString().trim()
        if(box.isBlank()) {
            ui.cameraStatus.text="Önce kutu/raf gir, otomatik kayıt durduruldu"
            counter.observe(null,SystemClock.elapsedRealtime(),blankScene)
            return
        }
        if(candidate==null) {
            counter.observe(null,SystemClock.elapsedRealtime(),blankScene)
            ui.cameraStatus.text="Etiket aranıyor · OCR ${durationMs}ms"
            return
        }
        val pct=(candidate.score*100).toInt()
        if(candidate.score < ScanRules.MIN_SCORE) {
            counter.observe(candidate,SystemClock.elapsedRealtime(),blankScene)
            ui.cameraStatus.text="Düşük güven %$pct · otomatik kayıt yok · ${durationMs}ms"
            return
        }
        val accepted=counter.observe(candidate,SystemClock.elapsedRealtime(),blankScene)
        if(accepted==null) {
            ui.cameraStatus.text="${candidate.sku} ${candidate.size} · %$pct · doğrulanıyor / kilitli · ${durationMs}ms"
            return
        }
        try {
            store.add(accepted.sku,accepted.size,box,accepted.score,accepted.rawText)
            prefs.edit().putString("box",box).apply()
            ui.lastScanText.text="✓ +1 ${accepted.sku} · ${accepted.size}"
            ui.cameraStatus.text="SAYILDI · $pct% · yeni etiket göster · OCR ${durationMs}ms"
            tone?.startTone(ToneGenerator.TONE_PROP_BEEP,110)
            try {
                val v=getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if(android.os.Build.VERSION.SDK_INT>=26) v.vibrate(VibrationEffect.createOneShot(55,VibrationEffect.DEFAULT_AMPLITUDE))
                else v.vibrate(55)
            } catch(_:Exception) { }
            updateTotals()
            ui.syncStatus.text="${store.pendingCount()} kayıt gönderilmeyi bekliyor"
            sheets.sync() // uses its own executor; never blocks OCR/UI
        } catch(e:Exception) {
            ui.cameraStatus.text="KAYIT BAŞARISIZ: ${e.message}"
            stopCamera() // do not silently skip inventory entries
        }
    }
    private fun updateTotals() {
        ui.totalText.text="Net ${store.total()}"
        // Sync callback owns the status line, so errors are never overwritten by the counter UI.
    }
    private fun toast(message:String)=Toast.makeText(this,message,Toast.LENGTH_LONG).show()
    override fun onDestroy() {
        timer.removeCallbacks(retry)
        stopCamera()
        recognizer.close()
        analyzerExecutor.shutdown()
        sheets.close()
        tone?.release()
        store.close()
        super.onDestroy()
    }
}
