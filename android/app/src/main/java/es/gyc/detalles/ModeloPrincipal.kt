package es.gyc.detalles

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import es.gyc.detalles.datos.Ajustes
import es.gyc.detalles.datos.Api
import es.gyc.detalles.datos.Articulo
import es.gyc.detalles.datos.ErrorApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

data class Estado(
    val url: String = "",
    val resultados: List<Articulo> = emptyList(),
    val articulo: Articulo? = null,
    val cargando: Boolean = false,
    val guardando: Boolean = false,
    val error: String? = null,
    val exito: String? = null,
    val aviso: String? = null,
)

class ModeloPrincipal(app: Application) : AndroidViewModel(app) {

    private val ajustes = Ajustes(app)
    private val _estado = MutableStateFlow(Estado())
    val estado = _estado.asStateFlow()

    init {
        viewModelScope.launch {
            _estado.update { it.copy(url = ajustes.url.first()) }
        }
    }

    private fun api() = Api(_estado.value.url)

    /** Traduce la excepcion a algo que se entienda con el movil en la mano. */
    private fun describir(e: Throwable): String = when (e) {
        is ErrorApi -> when {
            e.esFaltaDePermiso ->
                "NO se ha guardado. La API todavía no tiene permiso de " +
                    "escritura sobre el ERP; hay que pedirlo al administrador."
            else -> e.message ?: "Error ${e.codigo}"
        }
        is IOException -> "No se llega al servidor. Comprueba que estás en la " +
            "WiFi de la empresa y la dirección en Ajustes."
        else -> e.message ?: "Error inesperado"
    }

    fun guardarAjustes(url: String) = viewModelScope.launch {
        ajustes.guardarServidor(url)
        _estado.update {
            it.copy(
                url = Ajustes.normalizarUrl(url),
                error = null,
            )
        }
    }

    fun buscarPorCodigo(codigo: String) = viewModelScope.launch {
        _estado.update { it.copy(cargando = true, error = null, aviso = null) }
        runCatching { api().buscarPorCodigo(codigo) }
            .onSuccess { lista ->
                _estado.update {
                    it.copy(
                        resultados = lista,
                        cargando = false,
                        // Un codigo puede apuntar a varios articulos, y hay 62
                        // compartiendo el codigo '0'. Decirlo evita que alguien
                        // mida el articulo equivocado.
                        aviso = when {
                            lista.isEmpty() ->
                                "Ese código no corresponde a ningún artículo."
                            lista.size > 1 ->
                                "Ese código lo comparten ${lista.size} artículos. " +
                                    "Elige el correcto."
                            else -> null
                        },
                    )
                }
                if (lista.size == 1) abrir(lista.first())
            }
            .onFailure { e ->
                _estado.update { it.copy(cargando = false, error = describir(e)) }
            }
    }

    fun buscarPorTexto(termino: String) = viewModelScope.launch {
        runCatching { api().buscarPorTexto(termino) }
            .onSuccess { lista ->
                _estado.update { it.copy(resultados = lista, aviso = null) }
            }
            .onFailure { e -> _estado.update { it.copy(error = describir(e)) } }
    }

    fun abrir(articulo: Articulo) {
        _estado.update { it.copy(articulo = articulo, error = null, exito = null) }
    }

    fun cerrarArticulo() {
        _estado.update { it.copy(articulo = null, error = null, exito = null) }
    }

    fun guardarMedidas(medidas: Map<String, Double>) = viewModelScope.launch {
        val articulo = _estado.value.articulo ?: return@launch
        _estado.update { it.copy(guardando = true, error = null, exito = null) }

        runCatching {
            api().guardarMedidas(articulo.idArticulo, medidas)
            // Se relee del ERP en vez de asumir: si un trigger o una regla
            // cambiara algo, la pantalla enseñaría lo que hay de verdad.
            api().ficha(articulo.idArticulo)
        }
            .onSuccess { fresco ->
                _estado.update {
                    it.copy(
                        articulo = fresco,
                        guardando = false,
                        exito = "Guardado.",
                    )
                }
            }
            .onFailure { e ->
                _estado.update { it.copy(guardando = false, error = describir(e)) }
            }
    }

    fun subirFoto(fichero: File) = viewModelScope.launch {
        val articulo = _estado.value.articulo ?: return@launch
        _estado.update { it.copy(guardando = true, error = null, exito = null) }

        runCatching {
            val r = api().subirFoto(articulo.idArticulo, fichero)
            api().ficha(articulo.idArticulo) to r
        }
            .onSuccess { (fresco, r) ->
                fichero.delete()
                _estado.update {
                    it.copy(
                        articulo = fresco,
                        guardando = false,
                        exito = if (r.principal) "Foto subida como principal."
                                else "Foto subida.",
                    )
                }
            }
            .onFailure { e ->
                _estado.update { it.copy(guardando = false, error = describir(e)) }
            }
    }

    fun limpiarMensajes() {
        _estado.update { it.copy(error = null, exito = null, aviso = null) }
    }
}
