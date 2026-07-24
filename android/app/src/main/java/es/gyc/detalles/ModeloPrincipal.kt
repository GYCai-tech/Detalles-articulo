package es.gyc.detalles

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import es.gyc.detalles.datos.Api
import es.gyc.detalles.datos.Articulo
import es.gyc.detalles.datos.ErrorApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/**
 * Direccion fija de la API en la LAN de la empresa. Cambiarla exige
 * recompilar: es deliberado. La app se usa en un unico almacen contra un
 * unico servidor, y la pantalla de configuracion solo generaba llamadas de
 * "no me va" por URLs mal tecleadas.
 */
const val URL_SERVIDOR = "http://10.0.0.12:8000"

data class Estado(
    val resultados: List<Articulo> = emptyList(),
    val articulo: Articulo? = null,
    val cargando: Boolean = false,
    val guardando: Boolean = false,
    val error: String? = null,
    val exito: String? = null,
    val aviso: String? = null,
    /** Foto recien hecha, a la espera de decidir añadir o reemplazar. */
    val fotoPendiente: File? = null,
    /** EAN escaneado que no corresponde a ningun articulo, listo para vincular. */
    val codigoPendiente: String? = null,
    /** Se incrementa al subir foto para que la pantalla recargue la imagen. */
    val versionFoto: Int = 0,
)

class ModeloPrincipal(app: Application) : AndroidViewModel(app) {

    private val _estado = MutableStateFlow(Estado())
    val estado = _estado.asStateFlow()

    private fun api() = Api(URL_SERVIDOR)

    /** Traduce la excepcion a algo que se entienda con el movil en la mano. */
    private fun describir(e: Throwable): String = when (e) {
        is ErrorApi -> when {
            e.esFaltaDePermiso ->
                "NO se ha guardado. La API todavía no tiene permiso de " +
                    "escritura sobre el ERP; hay que pedirlo al administrador."
            else -> e.message ?: "Error ${e.codigo}"
        }
        is IOException -> "No se llega al servidor. Comprueba que estás en la " +
            "WiFi de la empresa."
        else -> e.message ?: "Error inesperado"
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
                                "El código $codigo no está vinculado a ningún " +
                                    "artículo. Busca el artículo por texto y " +
                                    "vincúlalo desde su ficha."
                            lista.size > 1 ->
                                "Ese código lo comparten ${lista.size} artículos. " +
                                    "Elige el correcto."
                            else -> null
                        },
                        // Se guarda el codigo huerfano: la ficha que se abra
                        // despues ofrecera vincularlo.
                        codigoPendiente = if (lista.isEmpty()) codigo
                                          else it.codigoPendiente,
                    )
                }
                if (lista.size == 1) abrir(lista.first())
            }
            .onFailure { e ->
                _estado.update { it.copy(cargando = false, error = describir(e)) }
            }
    }

    fun vincularCodigo() = viewModelScope.launch {
        val articulo = _estado.value.articulo ?: return@launch
        val codigo = _estado.value.codigoPendiente ?: return@launch
        _estado.update { it.copy(guardando = true, error = null, exito = null) }
        runCatching {
            val r = api().vincularCodigo(articulo.idArticulo, codigo)
            api().ficha(articulo.idArticulo) to r
        }
            .onSuccess { (fresco, r) ->
                _estado.update {
                    it.copy(
                        articulo = fresco,
                        guardando = false,
                        codigoPendiente = null,
                        exito = if (r.sinCambios) "Ese código ya estaba vinculado."
                                else "Código $codigo vinculado.",
                    )
                }
            }
            .onFailure { e ->
                _estado.update { it.copy(guardando = false, error = describir(e)) }
            }
    }

    fun descartarCodigoPendiente() {
        _estado.update { it.copy(codigoPendiente = null) }
    }

    // Cancelar la busqueda anterior al teclear evita que una respuesta vieja
    // que llega tarde pise a la nueva (comprobado contra la API: llegan
    // desordenadas al teclear rapido).
    private var busquedaEnCurso: Job? = null

    fun buscarPorTexto(termino: String) {
        busquedaEnCurso?.cancel()
        busquedaEnCurso = viewModelScope.launch {
            try {
                val lista = api().buscarPorTexto(termino)
                _estado.update { it.copy(resultados = lista, aviso = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _estado.update { it.copy(error = describir(e)) }
            }
        }
    }

    fun abrir(articulo: Articulo) {
        _estado.update { it.copy(articulo = articulo, error = null, exito = null) }
    }

    fun cerrarArticulo() {
        _estado.update { it.copy(articulo = null, error = null, exito = null) }
    }

    /**
     * Guarda lo que haya cambiado en la ficha: medidas y/o el código de
     * fabricante. codigoFabricante = null significa "no ha cambiado".
     */
    fun guardar(medidas: Map<String, Double>, codigoFabricante: String?) =
        viewModelScope.launch {
            val articulo = _estado.value.articulo ?: return@launch
            _estado.update { it.copy(guardando = true, error = null, exito = null) }

            runCatching {
                if (medidas.isNotEmpty()) {
                    api().guardarMedidas(articulo.idArticulo, medidas)
                }
                if (codigoFabricante != null) {
                    api().guardarFabricante(articulo.idArticulo, codigoFabricante)
                }
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

    /**
     * Punto de entrada desde la camara. Nunca se sube directamente: la foto
     * queda pendiente y la pantalla enseña la vista previa y pregunta
     * (subir / añadir / reemplazar / descartar).
     */
    fun subirFoto(fichero: File) {
        if (_estado.value.articulo == null) {
            fichero.delete()
            return
        }
        _estado.update { it.copy(fotoPendiente = fichero) }
    }

    fun confirmarFoto(reemplazar: Boolean) {
        val fichero = _estado.value.fotoPendiente ?: return
        _estado.update { it.copy(fotoPendiente = null) }
        subir(fichero, reemplazar)
    }

    fun cancelarFoto() {
        _estado.value.fotoPendiente?.delete()
        _estado.update { it.copy(fotoPendiente = null) }
    }

    private fun subir(fichero: File, reemplazar: Boolean) = viewModelScope.launch {
        val articulo = _estado.value.articulo ?: return@launch
        _estado.update { it.copy(guardando = true, error = null, exito = null) }

        runCatching {
            val r = api().subirFoto(articulo.idArticulo, fichero, reemplazar)
            api().ficha(articulo.idArticulo) to r
        }
            .onSuccess { (fresco, r) ->
                fichero.delete()
                _estado.update {
                    it.copy(
                        articulo = fresco,
                        guardando = false,
                        versionFoto = it.versionFoto + 1,
                        exito = when {
                            r.reemplazo -> "Foto reemplazada."
                            r.principal -> "Foto subida como principal."
                            else -> "Foto añadida."
                        },
                    )
                }
            }
            .onFailure { e ->
                _estado.update { it.copy(guardando = false, error = describir(e)) }
            }
    }

    /** La foto actual del articulo abierto, para mostrarla en la ficha. */
    suspend fun cargarFoto(): ByteArray? {
        val articulo = _estado.value.articulo ?: return null
        return runCatching { api().imagen(articulo.idArticulo) }.getOrNull()
    }

    fun limpiarMensajes() {
        _estado.update { it.copy(error = null, exito = null, aviso = null) }
    }
}
