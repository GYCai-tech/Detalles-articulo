package es.gyc.detalles.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import es.gyc.detalles.datos.Ajustes

/**
 * Configuracion del dispositivo: a que servidor se habla.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PantallaAjustes(
    urlActual: String,
    onGuardar: (String) -> Unit,
    onVolver: (() -> Unit)?,
) {
    var url by remember(urlActual) { mutableStateOf(urlActual) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Ajustes") },
            navigationIcon = {
                onVolver?.let {
                    IconButton(onClick = it) { Text("‹", style = MaterialTheme.typography.headlineMedium) }
                }
            },
        )
    }) { relleno ->
        Column(
            Modifier
                .padding(relleno)
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Seccion(
                icono = { Icon(Icons.Default.Dns, null) },
                titulo = "Servidor",
                explicacion = "Dirección de la API en la red de la empresa. " +
                    "Si no pones puerto se usa el 8200.",
            ) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Dirección") },
                    placeholder = { Text("10.0.0.12:8200") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge
                        .copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (url.isNotBlank()) {
                    Text(
                        "Se usará: ${Ajustes.normalizarUrl(url)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Button(
                onClick = { onGuardar(url) },
                enabled = url.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Guardar") }
        }
    }
}

@Composable
private fun Seccion(
    icono: @Composable () -> Unit,
    titulo: String,
    explicacion: String,
    contenido: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            icono()
            Text(titulo, style = MaterialTheme.typography.titleMedium)
        }
        Text(
            explicacion,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        contenido()
    }
}
