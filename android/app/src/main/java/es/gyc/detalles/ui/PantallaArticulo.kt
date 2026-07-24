package es.gyc.detalles.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import es.gyc.detalles.datos.Articulo
import es.gyc.detalles.datos.CAMPOS_MEDIDA
import es.gyc.detalles.datos.ayudaDe
import es.gyc.detalles.datos.etiquetaDe
import es.gyc.detalles.datos.unidadDe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PantallaArticulo(
    articulo: Articulo,
    guardando: Boolean,
    error: String?,
    exito: String?,
    versionFoto: Int,
    fotoPendiente: java.io.File?,
    codigoPendiente: String?,
    cargarFoto: suspend () -> ByteArray?,
    onGuardar: (Map<String, Double>, String?) -> Unit,
    onHacerFoto: () -> Unit,
    onVolver: () -> Unit,
    onFotoAnadir: () -> Unit,
    onFotoReemplazar: () -> Unit,
    onFotoCancelar: () -> Unit,
    onVincular: () -> Unit,
) {
    // Se parte de lo que ya hay en el ERP para que se vea qué se está pisando.
    val valores = remember(articulo.idArticulo) {
        mutableStateMapOf<String, String>().apply {
            CAMPOS_MEDIDA.forEach { campo ->
                put(campo, articulo.medidas[campo]?.let { formatear(it) } ?: "")
            }
        }
    }

    val editados = CAMPOS_MEDIDA.mapNotNull { campo ->
        val texto = valores[campo].orEmpty().replace(',', '.').trim()
        val numero = texto.toDoubleOrNull()
        val original = articulo.medidas[campo]
        if (numero != null && numero > 0 && numero != original) campo to numero else null
    }.toMap()

    var fabricante by remember(articulo.idArticulo) {
        mutableStateOf(articulo.codigoFabricante.orEmpty())
    }
    val fabricanteCambiado = fabricante.trim() != articulo.codigoFabricante.orEmpty()
    val totalCambios = editados.size + if (fabricanteCambiado) 1 else 0

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(articulo.idArticulo, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onVolver) {
                        Text("‹", style = MaterialTheme.typography.headlineMedium)
                    }
                },
            )
        },
        bottomBar = {
            Surface(shadowElevation = 8.dp) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick = onHacerFoto,
                        modifier = Modifier.weight(1f).height(52.dp),
                    ) {
                        Icon(Icons.Default.CameraAlt, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Foto")
                    }
                    Button(
                        onClick = {
                            onGuardar(
                                editados,
                                if (fabricanteCambiado) fabricante.trim() else null,
                            )
                        },
                        enabled = totalCambios > 0 && !guardando && !articulo.protegido,
                        modifier = Modifier.weight(1f).height(52.dp),
                    ) {
                        if (guardando) CircularProgressIndicator(
                            Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Text(
                            if (totalCambios == 0) "Guardar"
                            else "Guardar ($totalCambios)"
                        )
                    }
                }
            }
        },
    ) { relleno ->
        Column(
            Modifier.padding(relleno).padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(articulo.descrip.orEmpty(), style = MaterialTheme.typography.titleMedium)
            articulo.codBarras?.let {
                Text("EAN $it", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (articulo.tieneFoto) {
                // versionFoto cambia al subir una foto: fuerza recargarla.
                // El par distingue "cargando" de "no hay imagen": la base
                // arrastra miles de referencias cuyo fichero ya no existe, y
                // en ese caso no hay que dejar un spinner eterno.
                val carga by produceState(
                    false to null as ImageBitmap?,
                    articulo.idArticulo, versionFoto,
                ) {
                    val bmp = withContext(Dispatchers.IO) {
                        cargarFoto()?.let { bytes ->
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                ?.asImageBitmap()
                        }
                    }
                    value = true to bmp
                }
                val (terminada, bmp) = carga
                when {
                    !terminada -> Box(
                        Modifier.fillMaxWidth().height(120.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }

                    bmp != null -> Surface(
                        shape = MaterialTheme.shapes.medium,
                        tonalElevation = 1.dp,
                    ) {
                        Image(
                            bitmap = bmp,
                            contentDescription = "Foto del artículo",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp),
                        )
                    }
                    // terminada y sin imagen: referencia rota en el ERP, no
                    // se enseña nada.
                }
            }

            codigoPendiente?.let { codigo ->
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Text(
                            "El código escaneado $codigo no está vinculado " +
                                "a ningún artículo.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Button(
                            onClick = onVincular,
                            enabled = !guardando,
                            modifier = Modifier.padding(top = 8.dp),
                        ) { Text("Vincular a este artículo") }
                    }
                }
            }

            if (articulo.protegido) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text(
                        "Este artículo está protegido en el ERP. Cualquier cambio " +
                            "se revierte automáticamente, así que no se puede medir.",
                        Modifier.padding(14.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            HorizontalDivider()

            CAMPOS_MEDIDA.forEach { campo ->
                OutlinedTextField(
                    value = valores[campo].orEmpty(),
                    onValueChange = { valores[campo] = it },
                    label = { Text("${etiquetaDe(campo)} (${unidadDe(campo)})") },
                    singleLine = true,
                    enabled = !articulo.protegido,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    trailingIcon = {
                        if (campo in editados) {
                            Icon(Icons.Default.CheckCircle, "Modificado",
                                tint = MaterialTheme.colorScheme.primary)
                        }
                    },
                    supportingText = {
                        val original = articulo.medidas[campo]
                        val estado = if (original == null) "sin dato en el ERP"
                                     else "actualmente ${formatear(original)}"
                        Text("$estado · ${ayudaDe(campo)}")
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            OutlinedTextField(
                value = fabricante,
                onValueChange = { fabricante = it },
                label = { Text("Código de fabricante") },
                singleLine = true,
                enabled = !articulo.protegido,
                trailingIcon = {
                    if (fabricanteCambiado) {
                        Icon(Icons.Default.CheckCircle, "Modificado",
                            tint = MaterialTheme.colorScheme.primary)
                    }
                },
                supportingText = {
                    val original = articulo.codigoFabricante
                    val estado = if (original.isNullOrBlank()) "sin dato en el ERP"
                                 else "actualmente $original"
                    Text("$estado · La referencia del fabricante para este artículo.")
                },
                modifier = Modifier.fillMaxWidth(),
            )

            exito?.let { Aviso(it, MaterialTheme.colorScheme.primaryContainer) }
            error?.let { Aviso(it, MaterialTheme.colorScheme.errorContainer) }

            Spacer(Modifier.height(24.dp))
        }
    }

    fotoPendiente?.let { fichero ->
        // La foto ya viene reducida de Imagenes.reducir: decodificarla para
        // la vista previa es barato.
        val previa = remember(fichero) {
            BitmapFactory.decodeFile(fichero.absolutePath)?.asImageBitmap()
        }
        AlertDialog(
            onDismissRequest = onFotoCancelar,
            title = {
                Text(
                    if (articulo.tieneFoto) "Este artículo ya tiene foto"
                    else "¿Subir esta foto?"
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    previa?.let {
                        Image(
                            bitmap = it,
                            contentDescription = "Foto recién hecha",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
                        )
                    }
                    if (articulo.tieneFoto) {
                        Text(
                            "¿Añadir esta foto junto a la actual, o " +
                                "reemplazar la actual por esta?"
                        )
                    }
                }
            },
            confirmButton = {
                if (articulo.tieneFoto) {
                    Row {
                        TextButton(onClick = onFotoAnadir) { Text("Añadir") }
                        TextButton(onClick = onFotoReemplazar) { Text("Reemplazar") }
                    }
                } else {
                    TextButton(onClick = onFotoAnadir) { Text("Subir") }
                }
            },
            dismissButton = {
                TextButton(onClick = onFotoCancelar) { Text("Descartar") }
            },
        )
    }
}

@Composable
private fun Aviso(texto: String, color: androidx.compose.ui.graphics.Color) {
    Surface(color = color, shape = MaterialTheme.shapes.medium) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) { Text(texto, style = MaterialTheme.typography.bodyMedium) }
    }
}

/**
 * El ERP guarda decimal(38,14), asi que un peso sale como 10.48200035095215.
 * Mostrarlo entero en un movil no ayuda a nadie a medir.
 */
private fun formatear(valor: Double): String {
    val redondeado = Math.round(valor * 1000.0) / 1000.0
    return if (redondeado % 1.0 == 0.0) redondeado.toLong().toString()
    else redondeado.toString()
}
