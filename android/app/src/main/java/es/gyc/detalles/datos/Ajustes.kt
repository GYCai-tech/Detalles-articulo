package es.gyc.detalles.datos

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.almacen by preferencesDataStore(name = "ajustes")

/**
 * Ajustes del dispositivo. Solo la direccion del servidor: la app no tiene
 * login. En el almacen se usa un unico usuario, y es la API quien firma todo
 * en el ERP con ese nombre (API_USUARIO).
 */
class Ajustes(private val contexto: Context) {

    private val cUrl = stringPreferencesKey("url_servidor")

    val url: Flow<String> = contexto.almacen.data.map { it[cUrl] ?: "" }

    suspend fun guardarServidor(nuevaUrl: String) {
        contexto.almacen.edit {
            it[cUrl] = normalizarUrl(nuevaUrl)
        }
    }

    companion object {
        /**
         * Acepta lo que la gente escribe de verdad: "10.0.0.12", "10.0.0.12:8200",
         * "http://10.0.0.12:8200/". Sin esto, el primer fallo de todos es una
         * URL sin esquema y un mensaje de error que no explica nada.
         */
        fun normalizarUrl(bruta: String): String {
            var u = bruta.trim().removeSuffix("/")
            if (u.isEmpty()) return ""
            if (!u.startsWith("http://") && !u.startsWith("https://")) {
                u = "http://$u"
            }
            if (!u.substringAfter("://").contains(":")) u = "$u:8200"
            return u
        }
    }
}
