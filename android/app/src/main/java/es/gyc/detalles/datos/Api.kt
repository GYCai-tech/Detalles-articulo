package es.gyc.detalles.datos

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class Api(private val baseUrl: String) {

    private val json = Json { ignoreUnknownKeys = true }

    private val cliente = OkHttpClient.Builder()
        // El ERP no siempre responde rapido y el movil va por WiFi de nave.
        // Tiempos generosos evitan fallos que la gente interpreta como "no va".
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)  // subir fotos
        .build()

    private suspend fun ejecutar(peticion: Request): String = withContext(Dispatchers.IO) {
        cliente.newCall(peticion).execute().use { respuesta ->
            val cuerpo = respuesta.body?.string().orEmpty()
            if (!respuesta.isSuccessful) {
                var detalle = "Error ${respuesta.code}"
                var causa: String? = null
                runCatching {
                    val obj = json.parseToJsonElement(cuerpo) as JsonObject
                    obj["detail"]?.jsonPrimitive?.content?.let { detalle = it }
                    causa = obj["causa"]?.jsonPrimitive?.content
                }
                throw ErrorApi(respuesta.code, detalle, causa)
            }
            cuerpo
        }
    }

    suspend fun buscarPorCodigo(codigo: String): List<Articulo> {
        val texto = ejecutar(
            Request.Builder()
                .url("$baseUrl/articulos/buscar?codbarras=${cod(codigo)}")
                .build()
        )
        return json.decodeFromString<Resultados>(texto).resultados
    }

    suspend fun buscarPorTexto(termino: String): List<Articulo> {
        val texto = ejecutar(
            Request.Builder()
                .url("$baseUrl/articulos/buscar?texto=${cod(termino)}")
                .build()
        )
        return json.decodeFromString<Resultados>(texto).resultados
    }

    suspend fun pendientes(limite: Int = 50): List<Articulo> {
        val texto = ejecutar(
            Request.Builder()
                .url("$baseUrl/articulos/pendientes?limite=$limite")
                .build()
        )
        return json.decodeFromString<Resultados>(texto).resultados
    }

    suspend fun ficha(idArticulo: String): Articulo {
        val texto = ejecutar(
            Request.Builder()
                .url("$baseUrl/articulos/${cod(idArticulo)}")
                .build()
        )
        return json.decodeFromString(texto)
    }

    suspend fun guardarMedidas(
        idArticulo: String,
        medidas: Map<String, Double>,
    ): RespuestaMedidas {
        val cuerpo = buildString {
            append("{")
            append(medidas.entries.joinToString(",") { (k, v) -> "\"$k\":$v" })
            append("}")
        }.toRequestBody("application/json".toMediaType())

        val texto = ejecutar(
            Request.Builder()
                .url("$baseUrl/articulos/${cod(idArticulo)}/medidas")
                .put(cuerpo).build()
        )
        return json.decodeFromString(texto)
    }

    suspend fun subirFoto(
        idArticulo: String,
        fichero: File,
        reemplazar: Boolean = false,
    ): RespuestaFoto {
        val cuerpo = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart(
                "fichero", fichero.name,
                fichero.asRequestBody("image/jpeg".toMediaType())
            )
            .build()

        val texto = ejecutar(
            Request.Builder()
                .url("$baseUrl/articulos/${cod(idArticulo)}/foto?reemplazar=$reemplazar")
                .post(cuerpo).build()
        )
        return json.decodeFromString(texto)
    }

    /** La foto que muestra el ERP, o null si el articulo no tiene. */
    suspend fun imagen(idArticulo: String): ByteArray? = withContext(Dispatchers.IO) {
        val peticion = Request.Builder()
            .url("$baseUrl/articulos/${cod(idArticulo)}/imagen")
            .build()
        cliente.newCall(peticion).execute().use { respuesta ->
            if (respuesta.isSuccessful) respuesta.body?.bytes() else null
        }
    }

    /** Asocia el EAN escaneado al articulo (alta en Articulos_CodBarras). */
    suspend fun vincularCodigo(idArticulo: String, codigo: String): RespuestaVinculo {
        val cuerpo = json.encodeToString(Vinculo(codigo))
            .toRequestBody("application/json".toMediaType())
        val texto = ejecutar(
            Request.Builder()
                .url("$baseUrl/articulos/${cod(idArticulo)}/codbarras")
                .put(cuerpo).build()
        )
        return json.decodeFromString(texto)
    }

    /** Escribe el código del fabricante (ARTICULOS.CodigoFabricante). */
    suspend fun guardarFabricante(idArticulo: String, codigo: String): RespuestaFabricante {
        val cuerpo = json.encodeToString(Fabricante(codigo))
            .toRequestBody("application/json".toMediaType())
        val texto = ejecutar(
            Request.Builder()
                .url("$baseUrl/articulos/${cod(idArticulo)}/fabricante")
                .put(cuerpo).build()
        )
        return json.decodeFromString(texto)
    }

    companion object {
        /**
         * Imprescindible: existen articulos llamados `*`, `,,` y `GEN. PARTE`.
         * Sin codificar, la peticion ni sale del movil.
         *
         * El `+` que produce URLEncoder solo significa espacio en una query.
         * Estos valores tambien van en el segmento de ruta, donde `+` es un
         * `+` literal, asi que se sustituye por `%20`, que vale en los dos.
         */
        private fun cod(v: String): String =
            URLEncoder.encode(v, "UTF-8").replace("+", "%20")
    }
}
