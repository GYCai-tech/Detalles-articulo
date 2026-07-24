package es.gyc.detalles

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import es.gyc.detalles.datos.Imagenes
import es.gyc.detalles.ui.PantallaArticulo
import es.gyc.detalles.ui.PantallaBuscar
import java.io.File

class MainActivity : ComponentActivity() {

    private var ficheroFoto: File? = null
    private var alHacerFoto: ((File) -> Unit)? = null

    // Escáner físico de la PDA (Honeywell). No usa SDK: el servicio de escaneo
    // de la PDA emite un broadcast con el código leído si se configura así en
    // Ajustes -> Honeywell Settings -> Scanning -> Internal Scanner ->
    // Data Processing Settings:
    //   Data Intent = activado,  Intent Action = es.gyc.detalles.SCAN
    // (y desactivar el modo teclado/wedge para que no escriba el código en
    // cualquier campo con foco). El código llega en el extra "data".
    private var alEscanearHw: ((String) -> Unit)? = null

    private val receptorEscanerHw = object : BroadcastReceiver() {
        override fun onReceive(contexto: Context, intent: Intent) {
            val codigo = intent.getStringExtra("data")?.trim()
            if (!codigo.isNullOrBlank()) alEscanearHw?.invoke(codigo)
        }
    }

    override fun onStart() {
        super.onStart()
        // EXPORTED: el broadcast viene de la app de escaneo de Honeywell, no
        // de esta app. Sin ese flag, en Android 13+ nunca llegaría.
        ContextCompat.registerReceiver(
            this,
            receptorEscanerHw,
            IntentFilter(ACCION_ESCANER).apply { addCategory(Intent.CATEGORY_DEFAULT) },
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    override fun onStop() {
        unregisterReceiver(receptorEscanerHw)
        super.onStop()
    }

    companion object {
        /** Acción que hay que escribir en el Data Intent de la PDA. */
        const val ACCION_ESCANER = "es.gyc.detalles.SCAN"
    }

    private val camara = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { hecha ->
        val f = ficheroFoto
        if (hecha && f != null && f.length() > 0) {
            // Reducir aqui y no en el servidor: ahorra tambien el tiempo de
            // subida por la WiFi de la nave, que es donde mas se nota.
            alHacerFoto?.invoke(Imagenes.reducir(f))
        } else {
            f?.delete()
        }
        ficheroFoto = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // El escaner se descarga de Play Services la primera vez. Pedirlo al
        // arrancar evita que el primer escaneo del dia falle en el almacen.
        runCatching { ModuleInstall.getClient(this) }

        setContent {
            MaterialTheme {
                Surface { Raiz() }
            }
        }
    }

    @Composable
    private fun Raiz(modelo: ModeloPrincipal = viewModel()) {
        val estado by modelo.estado.collectAsState()

        // El gatillo de la PDA busca desde cualquier pantalla: en el almacén
        // se escanea el siguiente artículo sin volver atrás a mano.
        SideEffect { alEscanearHw = modelo::buscarPorCodigo }

        when {
            estado.articulo != null -> PantallaArticulo(
                articulo = estado.articulo!!,
                guardando = estado.guardando,
                error = estado.error,
                exito = estado.exito,
                versionFoto = estado.versionFoto,
                fotoPendiente = estado.fotoPendiente,
                codigoPendiente = estado.codigoPendiente,
                cargarFoto = modelo::cargarFoto,
                onGuardar = modelo::guardarMedidas,
                onHacerFoto = { hacerFoto(modelo::subirFoto) },
                onVolver = modelo::cerrarArticulo,
                onFotoAnadir = { modelo.confirmarFoto(reemplazar = false) },
                onFotoReemplazar = { modelo.confirmarFoto(reemplazar = true) },
                onFotoCancelar = modelo::cancelarFoto,
                onVincular = modelo::vincularCodigo,
            )

            else -> PantallaBuscar(
                resultados = estado.resultados,
                cargando = estado.cargando,
                error = estado.error,
                aviso = estado.aviso,
                onEscanear = { escanear(modelo::buscarPorCodigo) },
                onBuscarTexto = modelo::buscarPorTexto,
                onAbrir = modelo::abrir,
            )
        }
    }

    private fun escanear(onCodigo: (String) -> Unit) {
        val opciones = GmsBarcodeScannerOptions.Builder()
            // Los codigos del ERP son EAN-13 y EAN-8; acotar los formatos hace
            // la lectura mas rapida y evita leer un QR de una caja por error.
            .setBarcodeFormats(
                Barcode.FORMAT_EAN_13,
                Barcode.FORMAT_EAN_8,
                Barcode.FORMAT_UPC_A,
                Barcode.FORMAT_CODE_128,
            )
            .enableAutoZoom()
            .build()

        GmsBarcodeScanning.getClient(this, opciones)
            .startScan()
            .addOnSuccessListener { codigo ->
                codigo.rawValue?.takeIf { it.isNotBlank() }?.let(onCodigo)
            }
    }

    private fun hacerFoto(alTerminar: (File) -> Unit) {
        val carpeta = File(cacheDir, "fotos").apply { mkdirs() }
        val destino = File(carpeta, "foto_${System.currentTimeMillis()}.jpg")
        ficheroFoto = destino
        alHacerFoto = alTerminar

        val uri: Uri = FileProvider.getUriForFile(
            this, "$packageName.ficheros", destino
        )
        camara.launch(uri)
    }
}
