package es.gyc.detalles.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import es.gyc.detalles.datos.Articulo
import es.gyc.detalles.datos.CAMPOS_MEDIDA

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PantallaBuscar(
    resultados: List<Articulo>,
    cargando: Boolean,
    error: String?,
    aviso: String?,
    onEscanear: () -> Unit,
    onBuscarTexto: (String) -> Unit,
    onAbrir: (Articulo) -> Unit,
    onAjustes: () -> Unit,
) {
    var termino by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Buscar artículo") },
                actions = {
                    IconButton(onClick = onAjustes) {
                        Icon(Icons.Default.Settings, "Ajustes")
                    }
                },
            )
        },
        floatingActionButton = {
            // Respaldo para móviles sin escáner físico. En la PDA el gatillo
            // funciona directamente, sin tocar la pantalla.
            ExtendedFloatingActionButton(
                onClick = onEscanear,
                icon = { Icon(Icons.Default.QrCodeScanner, null) },
                text = { Text("Cámara") },
            )
        },
    ) { relleno ->
        Column(Modifier.padding(relleno).fillMaxSize()) {

            OutlinedTextField(
                value = termino,
                onValueChange = {
                    termino = it
                    if (it.length >= 3) onBuscarTexto(it)
                },
                label = { Text("Código o descripción") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )

            if (cargando) LinearProgressIndicator(Modifier.fillMaxWidth())

            aviso?.let {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                    Text(it, Modifier.fillMaxWidth().padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium)
                }
            }

            error?.let {
                Surface(color = MaterialTheme.colorScheme.errorContainer) {
                    Text(it, Modifier.fillMaxWidth().padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }

            LazyColumn(Modifier.weight(1f)) {
                items(resultados, key = { it.idArticulo }) { articulo ->
                    FilaArticulo(articulo) { onAbrir(articulo) }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun FilaArticulo(articulo: Articulo, onClick: () -> Unit) {
    val faltan = CAMPOS_MEDIDA.count { articulo.medidas[it] == null }

    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(articulo.idArticulo, style = MaterialTheme.typography.titleSmall)
            Text(
                articulo.descrip.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }

        // Dos indicadores de un vistazo: cuanto falta por medir y si hay foto.
        if (faltan > 0) {
            AssistChip(
                onClick = onClick,
                leadingIcon = { Icon(Icons.Default.Straighten, null, Modifier.size(16.dp)) },
                label = { Text("$faltan") },
            )
        }
        Icon(
            Icons.Default.CameraAlt,
            contentDescription = if (articulo.tieneFoto) "Tiene foto" else "Sin foto",
            tint = if (articulo.tieneFoto) MaterialTheme.colorScheme.primary
                   else MaterialTheme.colorScheme.outlineVariant,
        )
    }
}
