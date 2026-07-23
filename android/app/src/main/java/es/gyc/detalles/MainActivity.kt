package es.gyc.detalles

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import es.gyc.detalles.datos.Imagenes
import es.gyc.detalles.ui.PantallaAjustes
import es.gyc.detalles.ui.PantallaArticulo
import es.gyc.detalles.ui.PantallaBuscar
import java.io.File

class MainActivity : ComponentActivity() {

    private var ficheroFoto: File? = null
    private var alHacerFoto: ((File) -> Unit)? = null

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
        var enAjustes by remember { mutableStateOf(false) }

        // Sin servidor configurado no hay nada que hacer: se entra por ajustes.
        LaunchedEffect(estado.url) {
            if (estado.url.isBlank()) enAjustes = true
        }

        when {
            enAjustes -> PantallaAjustes(
                urlActual = estado.url,
                onGuardar = { url ->
                    modelo.guardarAjustes(url)
                    enAjustes = false
                },
                onVolver = if (estado.url.isBlank()) null else ({ enAjustes = false }),
            )

            estado.articulo != null -> PantallaArticulo(
                articulo = estado.articulo!!,
                guardando = estado.guardando,
                error = estado.error,
                exito = estado.exito,
                onGuardar = modelo::guardarMedidas,
                onHacerFoto = { hacerFoto(modelo::subirFoto) },
                onVolver = modelo::cerrarArticulo,
            )

            else -> PantallaBuscar(
                resultados = estado.resultados,
                cargando = estado.cargando,
                error = estado.error,
                aviso = estado.aviso,
                onEscanear = { escanear(modelo::buscarPorCodigo) },
                onBuscarTexto = modelo::buscarPorTexto,
                onAbrir = modelo::abrir,
                onAjustes = { enAjustes = true },
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
