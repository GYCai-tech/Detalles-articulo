"""Reglas de negocio verificadas contra el ERP antes de tocar dbo.ARTICULOS.

Todo lo de aqui sale de auditar el esquema y el trigger dbo.Articulos_UTrig
(1.093 lineas). No son suposiciones; si algo cambia en el ERP, hay que reauditar.
"""

# --- Columnas ---------------------------------------------------------------

# Unicas columnas que este proyecto puede escribir. El trigger Articulos_UTrig
# no tiene ninguna guarda IF UPDATE() ni comparacion Inserted/Deleted sobre
# ellas: un UPDATE que solo las toque no dispara ninguna escritura en cascada.
#
# OJO con Profundo: no esta en la vista VArticulos (el formulario estandar de
# AHORA probablemente ni la ensena), pero si la leen las etiquetas de
# fabricacion (persV_DatosEtiquetaFabricacion), el calculo de cajas
# (persVCajasAlbaran) y los palets (PersVPedidosPaletsLineas). Rellenarla
# cambia lo que se imprime en las etiquetas. Es el efecto que se busca, pero
# conviene avisar a produccion antes de tocarla en masa.
COLUMNAS_MEDIDA = ("Peso", "Alto", "Ancho", "Largo", "Profundo")

# Columnas de auditoria de AHORA. Son NOT NULL y las rellenamos siempre.
COL_USUARIO = "Usuario"
COL_FECHA = "FechaInsertUpdate"

# PROHIBIDO tocar. CantidadBulto la recalcula el propio trigger llamando a
# funDisposicion_Paletizado_getCantidadBulto cuando cambia IdEmbalaje.
# Escribirla a mano deja el articulo incoherente con su embalaje.
COLUMNAS_PROHIBIDAS = ("CantidadBulto", "IdEmbalaje", "IdFamilia", "IdArticulo")

# --- Guardas del trigger ----------------------------------------------------

# Articulos_UTrig hace ROLLBACK TRANSACTION incondicional si el UPDATE toca
# cualquiera de estos tres articulos genericos. Como el trigger es POR STATEMENT
# y no por fila, incluir uno solo revierte el lote entero. Los tres existen en
# produccion, asi que la exclusion no es teorica.
ARTICULOS_PROTEGIDOS = (
    "GEN. PARTE",
    "PARTE_PRIMASALIDA",
    "GEN. TRABAJO EXTERNO",
)

# La otra guarda incondicional rechaza filas con IdFamilia NULL. Hoy hay 0 en
# produccion, pero el UPDATE nunca toca IdFamilia, asi que no puede provocarla.

# --- Validacion de valores --------------------------------------------------

# CUIDADO CON LAS UNIDADES: no son homogeneas en esta tabla.
#
# Producto de catalogo -> centimetros.
#     JAULA ECONOMICA-3   Profundo=104 Ancho=63 Alto=150,
#     y la ficha de catalogo dice "104*63*150 cm".
#
# Materia prima y fabricacion -> milimetros.
#     CHAPA GALVA. FORMATO DE 3000x1500x1,5  -> Profundo=3000 Ancho=1500 Alto=1,5
#     TUBO 30x30 INICIAL 2,16m               -> Largo=2165
#
# Conviven los dos sistemas en las mismas columnas. Este proyecto apunta al
# catalogo comercial (~578 articulos), que va en cm, pero los limites de abajo
# tienen que admitir tambien los valores en mm que ya existen: por eso el
# maximo es alto y no sirve para detectar un error de unidad.
#
# Son cotas de cordura, no validacion real: ya hay basura en la tabla (un Peso
# de -113,4 kg y un Alto de 261.010) y la idea es no anadir mas.
LIMITES = {
    "Peso": (0.0001, 15000.0),     # kg
    "Alto": (0.01, 10000.0),
    "Ancho": (0.01, 10000.0),
    "Largo": (0.01, 10000.0),
    "Profundo": (0.01, 10000.0),
}

# Largo y Profundo NO son sinonimos, y confundirlos es el error mas probable.
#
# Verificado cruzando 370 articulos del catalogo con la tabla: el campo "largo"
# de las fichas se corresponde con Profundo (147 coincidencias) y no con Largo
# (27). Con los tres valores a la vez, el mapeo largo->Profundo, ancho->Ancho,
# alto->Alto encaja en 88 articulos; el siguiente mejor, en 16.
#
#   Profundo -> el fondo de un objeto 3D, o el lado largo de una chapa
#   Largo    -> longitud de producto lineal: tubos, arcos, perfiles, barras
MAPEO_CATALOGO = {
    "largo": "Profundo",
    "ancho": "Ancho",
    "alto": "Alto",
}

# AHORA guarda "sin dato" como 0, no como NULL. Un 0 en el fichero de entrada
# significa "no tengo este dato", y se ignora en vez de escribirse.
SIN_DATO = 0


def validar(columna: str, valor: float) -> str | None:
    """Devuelve el motivo del rechazo, o None si el valor es aceptable."""
    minimo, maximo = LIMITES[columna]
    if valor < minimo:
        return f"{columna}={valor} por debajo del minimo {minimo}"
    if valor > maximo:
        return f"{columna}={valor} por encima del maximo {maximo}"
    return None
