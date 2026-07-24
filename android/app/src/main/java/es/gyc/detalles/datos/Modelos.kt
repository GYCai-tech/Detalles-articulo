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
    val reemplazo: Boolean = false,
    val usuario: String? = null,
    val estado: String = "",
)

@Serializable
data class Vinculo(val codigo: String)

@Serializable
data class RespuestaVinculo(
    val idArticulo: String,
    val codigo: String,
    val sinCambios: Boolean = false,
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
 * Campos que pide la app: peso y las tres dimensiones. Decision del
 * responsable (07/2026): no hace falta mas.
 *
 * El "largo" de las fichas de catalogo es la columna Profundo del ERP
 * (comprobado cruzando 370 articulos: 147 aciertos frente a 27 de Largo).
 * La columna Largo del ERP (largo de barra o tubo, solo producto lineal) NO
 * se pide desde la app; la API la sigue aceptando por si algun dia vuelve.
 */
val CAMPOS_MEDIDA = listOf("Peso", "Alto", "Ancho", "Profundo")

fun unidadDe(campo: String): String = if (campo == "Peso") "kg" else "cm"

fun etiquetaDe(campo: String): String = when (campo) {
    "Profundo" -> "Largo / fondo"
    else -> campo
}

/** Ayuda bajo cada campo. Explicar aqui evita rellenar la columna equivocada. */
fun ayudaDe(campo: String): String = when (campo) {
    "Peso" -> "En kilos."
    "Alto" -> "Altura del producto montado."
    "Ancho" -> "Anchura."
    "Profundo" -> "El largo del producto (las fichas de catálogo lo llaman «largo»)."
    else -> ""
}
