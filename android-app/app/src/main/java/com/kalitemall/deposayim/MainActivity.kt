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
import android.graphics.Bitmap
import android.graphics.RectF
import android.view.View
import androidx.camera.core.ImageProxy
import androidx.camera.core.UseCaseGroup
import java.util.concurrent.atomic.AtomicBoolean
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
    private var latestReading: ScanCandidate? = null
    @Volatile private var scanZones: ScanZones? = null
    private val ocrBusy=AtomicBoolean(false)
    @Volatile private var fullFrameMode=false
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
        ui.autoSwitch.isChecked=prefs.getBoolean("auto_enabled",true)
        ui.autoSwitch.setOnCheckedChangeListener { _, enabled ->
            prefs.edit().putBoolean("auto_enabled",enabled).apply()
            if(!enabled) counter.reset()
            ui.cameraStatus.text=if(enabled) "Otomatik sayım açık" else "Manuel sayım modu"
        }
        fullFrameMode=prefs.getBoolean("full_frame_fallback",false)
        ui.fullFrameSwitch.isChecked=fullFrameMode
        updateScanOverlays()
        ui.fullFrameSwitch.setOnCheckedChangeListener { _, enabled ->
            fullFrameMode=enabled
            prefs.edit().putBoolean("full_frame_fallback",enabled).apply()
            counter.reset()
            updateScanOverlays()
            ui.cameraStatus.text=if(enabled) "Tam etiket modu" else "İki alanla hızlı tarama"
        }
        ui.cameraPreview.addOnLayoutChangeListener { _,_,_,_,_,_,_,_,_ -> updateRoiBounds() }
        ui.dualZonesOverlay.addOnLayoutChangeListener { _,_,_,_,_,_,_,_,_ -> updateRoiBounds() }
        ui.codeTarget.addOnLayoutChangeListener { _,_,_,_,_,_,_,_,_ -> updateRoiBounds() }
        ui.sizeTarget.addOnLayoutChangeListener { _,_,_,_,_,_,_,_,_ -> updateRoiBounds() }
        ui.cameraPreview.post { updateRoiBounds() }
        ui.manualAddButton.setOnClickListener { addManualCount() }
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
    private fun updateScanOverlays() {
        ui.dualZonesOverlay.visibility=if(fullFrameMode) View.GONE else View.VISIBLE
        ui.fullFrameGuide.visibility=if(fullFrameMode) View.VISIBLE else View.GONE
        ui.cameraPreview.post { updateRoiBounds() }
    }

    private fun updateRoiBounds() {
        val preview=ui.cameraPreview
        if(preview.width<=0 || preview.height<=0 || ui.codeTarget.width<=0 || ui.sizeTarget.width<=0) return
        val origin=IntArray(2)
        preview.getLocationInWindow(origin)
        fun norm(view:View): NormRect {
            val xy=IntArray(2)
            view.getLocationInWindow(xy)
            return NormRect(
                ((xy[0]-origin[0]).toFloat()/preview.width).coerceIn(0f,1f),
                ((xy[1]-origin[1]).toFloat()/preview.height).coerceIn(0f,1f),
                ((xy[0]-origin[0]+view.width).toFloat()/preview.width).coerceIn(0f,1f),
                ((xy[1]-origin[1]+view.height).toFloat()/preview.height).coerceIn(0f,1f)
            )
        }
        scanZones=ScanZones(norm(ui.codeTarget),norm(ui.sizeTarget))
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
                val viewPort=ui.cameraPreview.viewPort
                if(viewPort==null) {
                    ui.cameraStatus.text="Önizleme hazırlanıyor; kamerayı yeniden aç"
                    return@addListener
                }
                provider=p
                val preview=Preview.Builder().build().also { it.setSurfaceProvider(ui.cameraPreview.surfaceProvider) }
                val analysis=ImageAnalysis.Builder()
                    .setTargetResolution(Size(1280,720))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analyzerExecutor) { imageProxy -> analyzeFrame(imageProxy) }
                p.unbindAll()
                val group=UseCaseGroup.Builder().addUseCase(preview).addUseCase(analysis)
                    .setViewPort(viewPort).build()
                boundCamera=p.bindToLifecycle(this,CameraSelector.DEFAULT_BACK_CAMERA,group)
                cameraOn=true
                counter.reset()
                ui.cameraButton.text="Kamerayı durdur"
                ui.cameraStatus.text="Hızlı tarama açık · iki yeşil kutuyu etikete hizala"
            } catch(e:Exception) { ui.cameraStatus.text="Kamera açılamadı: ${e.message}";cameraOn=false }
        },ContextCompat.getMainExecutor(this))
    }

    private fun analyzeFrame(imageProxy: ImageProxy) {
        if (!cameraOn || !ocrBusy.compareAndSet(false,true)) {
            imageProxy.close()
            return
        }
        val started=SystemClock.elapsedRealtime()
        if(fullFrameMode) {
            val cameraImage=imageProxy.image
            if(cameraImage==null) {
                ocrBusy.set(false);imageProxy.close();return
            }
            val rotation=imageProxy.imageInfo.rotationDegrees
            val width=if(rotation==90 || rotation==270) imageProxy.height else imageProxy.width
            val height=if(rotation==90 || rotation==270) imageProxy.width else imageProxy.height
            try {
                val input=InputImage.fromMediaImage(cameraImage,rotation)
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
                        onReading(found,SystemClock.elapsedRealtime()-started,
                            lines.none { it.centerX in .08f..92f && it.centerY in .12f..88f },text.text)
                    }
                    .addOnFailureListener { e -> ui.cameraStatus.text="OCR hatası: ${e.message}" }
                    .addOnCompleteListener { imageProxy.close(); ocrBusy.set(false) }
            } catch(e:Exception) {
                imageProxy.close();ocrBusy.set(false)
            }
            return
        }
        val zones=scanZones
        if(zones==null || !zones.code.valid() || !zones.size.valid()) {
            imageProxy.close();ocrBusy.set(false);return
        }
        val prepared: CroppedOcrFrame
        try {
            prepared=RoiScanner.prepare(imageProxy,zones)
        } catch(e:Exception) {
            imageProxy.close();ocrBusy.set(false)
            runOnUiThread { ui.cameraStatus.text="Bölge kesme hatası: ${e.message}" }
            return
        }
        // InputImage.fromBitmap owns the independent image; release ImageProxy immediately.
        imageProxy.close()
        val input=InputImage.fromBitmap(prepared.bitmap,0)
        try {
            recognizer.process(input)
                .addOnSuccessListener { result ->
                    val codeLines=mutableListOf<OcrLine>()
                    val sizeLines=mutableListOf<OcrLine>()
                    result.textBlocks.forEachIndexed { group,block ->
                        block.lines.forEach { line ->
                            val box=line.boundingBox ?: return@forEach
                            val l=OcrLine(line.text,line.confidence,
                                box.exactCenterX()/prepared.bitmap.width,
                                box.exactCenterY()/prepared.bitmap.height,group)
                            if(box.exactCenterX()<prepared.sizeStartX-prepared.gapPx/2f)
                                codeLines.add(l)
                            else if(box.exactCenterX()>prepared.sizeStartX-prepared.gapPx/2f)
                                sizeLines.add(l)
                        }
                    }
                    val code=ScanRules.codeFromRegion(codeLines)
                    val size=ScanRules.sizeFromRegion(sizeLines)
                    val accepted=ScanRules.parseDual(codeLines,sizeLines)
                    val raw="KOD: "+codeLines.joinToString(" ") { it.text }+
                        " | BEDEN: "+sizeLines.joinToString(" ") { it.text }
                    onReading(accepted,SystemClock.elapsedRealtime()-started,
                        codeLines.isEmpty() && sizeLines.isEmpty(),raw,code,size)
                }
                .addOnFailureListener { e -> ui.cameraStatus.text="OCR hatası: ${e.message}" }
                .addOnCompleteListener { prepared.bitmap.recycle(); ocrBusy.set(false) }
        } catch(e:Exception) {
            prepared.bitmap.recycle();ocrBusy.set(false)
        }
    }
    private fun stopCamera() {
        boundCamera?.cameraControl?.enableTorch(false)
        boundCamera=null;torchEnabled=false
        ui.torchButton.text="Fener"
        provider?.unbindAll();cameraOn=false;counter.reset()
        ui.cameraButton.text="Kamerayı aç"
        ui.cameraStatus.text="Kamera durduruldu"
    }
    private fun onReading(candidate:ScanCandidate?, durationMs:Long, blankScene:Boolean,
                          rawOcr:String, partialSku:String?=null, partialSize:String?=null) {
        if(!cameraOn) return
        latestReading=candidate
        ui.rawOcrText.text="Kameranın gördüğü yazı: "+
            rawOcr.replace('\n',' ').replace('\r',' ').take(200).ifBlank { "—" }
        val seenSku=candidate?.sku ?: partialSku
        val seenSize=candidate?.size ?: partialSize
        ui.readStatusText.text="STOK: ${seenSku ?: "—"}   BEDEN: ${seenSize ?: "—"}"
        // The fields are only suggestions; operator edits remain untouched.
        if(!ui.detectedSkuInput.hasFocus() && !ui.detectedSizeInput.hasFocus()) {
            if(seenSku!=null) {
                if(ui.detectedSkuInput.text.toString()!=seenSku && seenSize==null)
                    ui.detectedSizeInput.setText("")
                ui.detectedSkuInput.setText(seenSku)
            }
            if(seenSize!=null) ui.detectedSizeInput.setText(seenSize)
        }
        val box=ui.boxInput.text.toString().trim()
        if(box.isBlank()) {
            ui.cameraStatus.text="Önce kutu/raf gir; kayıt durduruldu"
            counter.observe(null,SystemClock.elapsedRealtime(),blankScene)
            return
        }
        if(candidate==null) {
            counter.observe(null,SystemClock.elapsedRealtime(),blankScene)
            ui.cameraStatus.text="Etiket aranıyor · OCR ${durationMs}ms"
            return
        }
        if(!ui.autoSwitch.isChecked || ui.detectedSkuInput.hasFocus() || ui.detectedSizeInput.hasFocus()) {
            ui.cameraStatus.text="${candidate.sku} ${candidate.size} · manuel kontrol · ${durationMs}ms"
            return
        }
        val accepted=counter.observe(candidate,SystemClock.elapsedRealtime(),blankScene)
        if(accepted==null) {
            ui.cameraStatus.text="${candidate.sku} ${candidate.size} · doğrulanıyor / kilitli · ${durationMs}ms"
            return
        }
        persistCount(accepted.sku,accepted.size,accepted.score,accepted.rawText,"OCR")
    }

    private fun addManualCount() {
        val sku=ScanRules.normalize(ui.detectedSkuInput.text.toString()).trim()
            .replace('–','-').replace('—','-').replace(Regex("\\s+"),"")
        val size=ScanRules.normalize(ui.detectedSizeInput.text.toString()).trim()
            .replace("STANDART","STD")
        val skuPattern=Regex("[0-9]{3}-[0-9]{4}-[RKB][0-9]{2}")
        val sizePattern=Regex("STD|SM|LXL|XS|S|M|L|XL|XXL|XXXL|3XL|4XL|[0-9]{2}")
        if(!skuPattern.matches(sku) || !sizePattern.matches(size)) {
            toast("Ürün kodunu ve bedeni kontrol et: örn. 317-2053-R56 / 40")
            return
        }
        val box=ui.boxInput.text.toString().trim()
        if(box.isBlank()) { toast("Kutu/raf zorunlu");return }
        // Lock the physical label already in view; next OCR frame must not double count.
        val visible=latestReading
        counter.lockCurrent(visible?.sku ?: sku,visible?.size ?: size)
        ui.detectedSkuInput.clearFocus()
        ui.detectedSizeInput.clearFocus()
        persistCount(sku,size,visible?.score ?: 0f,ui.rawOcrText.text.toString(),"MANUEL")
    }

    @Suppress("DEPRECATION")
    private fun persistCount(sku:String,size:String,score:Float,raw:String,source:String) {
        try {
            val box=ui.boxInput.text.toString().trim()
            if(box.isBlank()) { toast("Kutu/raf zorunlu");return }
            store.add(sku,size,box,score,raw,source)
            prefs.edit().putString("box",box).apply()
            ui.lastScanText.text="✓ +1 ${sku} · ${size} (${source})"
            ui.cameraStatus.text="SAYILDI · ${sku} ${size} · yeni etiket göster"
            tone?.startTone(ToneGenerator.TONE_PROP_BEEP,110)
            try {
                val v=getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if(android.os.Build.VERSION.SDK_INT>=26) v.vibrate(VibrationEffect.createOneShot(55,VibrationEffect.DEFAULT_AMPLITUDE))
                else v.vibrate(55)
            } catch(_:Exception) { }
            updateTotals()
            ui.syncStatus.text="${store.pendingCount()} kayıt gönderilmeyi bekliyor"
            sheets.sync()
        } catch(e:Exception) {
            ui.cameraStatus.text="KAYIT BAŞARISIZ: ${e.message}"
            stopCamera()
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
