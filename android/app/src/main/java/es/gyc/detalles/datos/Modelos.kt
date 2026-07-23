package es.gyc.detalles.datos

import kotlinx.serialization.Serializable

@Serializable
data class Articulo(
    val idArticulo: String,
    val descrip: String? = null,
    val idFamilia: String? = null,
    val codBarras: String? = null,
    val tieneFoto: Boolean = false,
    val medidas: Map<String, Double?> = emptyMap(),
    val protegido: Boolean = false,
)

@Serializable
data class Resultados(val resultados: List<Articulo> = emptyList())

@Serializable
data class Cambio(val antes: Double? = null, val despues: Double? = null)

@Serializable
data class RespuestaMedidas(
    val idArticulo: String,
    val usuario: String? = null,
    val cambios: Map<String, Cambio> = emptyMap(),
    val sinCambios: Boolean = false,
)

@Serializable
data class RespuestaFoto(
    val id: Int,
    val fichero: String,
    val principal: Boolean = false,
    val usuario: String? = null,
    val estado: String = "",
)

/** Error del servidor con el detalle que devuelve FastAPI. */
class ErrorApi(
    val codigo: Int,
    mensaje: String,
    /** La API marca asi la falta de credencial de escritura sobre el ERP. */
    val causa: String? = null,
) : Exception(mensaje) {

    /**
     * Un 503 con causa `sin_credenciales` no es un fallo de red ni algo que se
     * arregle reintentando: la API no tiene permiso de escritura y el dato NO
     * se ha guardado. Reintentar en bucle solo confundiria a quien mide.
     */
    val esFaltaDePermiso: Boolean get() = causa == "sin_credenciales"
}

/**
 * Campos que se pueden medir, en el orden en que se presentan.
 *
 * 'Profundo' va antes que 'Largo' a proposito: para un producto de catalogo,
 * lo que se mide es Alto/Ancho/Profundo. 'Largo' es otra cosa (ver abajo) y se
 * deja el ultimo para que no se rellene por inercia.
 */
val CAMPOS_MEDIDA = listOf("Peso", "Alto", "Ancho", "Profundo", "Largo")

fun unidadDe(campo: String): String = if (campo == "Peso") "kg" else "cm"

/**
 * Etiqueta del campo. 'Largo' y 'Profundo' NO son intercambiables en este ERP,
 * y confundirlos es el error mas facil de cometer con el movil en la mano.
 *
 * Comprobado cruzando 370 articulos del catalogo con la tabla: el "largo" de
 * las fichas de catalogo se corresponde con la columna Profundo (147 aciertos
 * frente a 27 de Largo). Largo se usa para producto lineal: tubos, arcos,
 * perfiles, barras.
 */
fun etiquetaDe(campo: String): String = when (campo) {
    "Profundo" -> "Fondo / largo del producto"
    "Largo" -> "Largo de barra o tubo"
    else -> campo
}

/** Ayuda bajo cada campo. Explicar aqui evita rellenar la columna equivocada. */
fun ayudaDe(campo: String): String = when (campo) {
    "Peso" -> "En kilos."
    "Alto" -> "Altura del producto montado."
    "Ancho" -> "Anchura."
    "Profundo" -> "El fondo. Es lo que las fichas de catálogo llaman «largo»."
    "Largo" -> "Solo para tubos, perfiles, arcos y barras. " +
        "Para una jaula o un gallinero, déjalo vacío."
    else -> ""
}
