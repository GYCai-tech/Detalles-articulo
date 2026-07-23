"""API de detalles de articulos. Unico punto con acceso de escritura al ERP.

Arrancar:  uvicorn api.main:app --host 0.0.0.0 --port 8200
Docs:      http://<host>:8200/docs
"""

from pathlib import Path
from uuid import uuid4

from fastapi import (FastAPI, File, HTTPException, Query,
                     Request, UploadFile, status)
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

from medidas.db import SinCredenciales, motor_escritura, motor_lectura
from medidas.reglas import COLUMNAS_MEDIDA

from . import config, erp, local

# La app no tiene login: herramienta interna en LAN con un unico usuario de
# almacen. Todo lo que se escribe en el ERP se firma con este nombre.
USUARIO = config.USUARIO

app = FastAPI(title="Detalles de articulos", version="0.1.0")


@app.exception_handler(SinCredenciales)
def sin_credenciales(_: Request, exc: SinCredenciales) -> JSONResponse:
    """503 y no 500: la API esta bien, le falta configuracion.

    Es el estado normal mientras no exista el login de escritura sobre
    dbo.ARTICULOS, y la app debe distinguirlo de un fallo real para poder
    decirle al usuario que su medida no se ha guardado.
    """
    return JSONResponse(status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                        content={"detail": str(exc), "causa": "sin_credenciales"})


@app.on_event("startup")
def arrancar() -> None:
    config.comprobar()
    local.inicializar()


# --- Modelos ----------------------------------------------------------------

class Medidas(BaseModel):
    """Todos opcionales: la app envia solo lo que se ha medido."""
    Peso: float | None = Field(None, gt=0)
    Alto: float | None = Field(None, gt=0)
    Ancho: float | None = Field(None, gt=0)
    Largo: float | None = Field(None, gt=0)
    Profundo: float | None = Field(None, gt=0)


# --- Consulta ---------------------------------------------------------------

@app.get("/articulos/buscar")
def buscar(codbarras: str | None = Query(None),
           texto: str | None = Query(None)):
    """Escaner primero, texto como respaldo.

    Devuelve siempre una lista: 62 articulos comparten CodBarras '0' y algunos
    codigos de prueba estan repetidos, asi que la app tiene que saber
    desambiguar en vez de asumir un unico resultado.
    """
    if codbarras:
        return {"resultados": erp.buscar_por_codbarras(codbarras)}
    if texto:
        return {"resultados": erp.buscar_por_texto(texto)}
    raise HTTPException(status.HTTP_400_BAD_REQUEST,
                        "indica 'codbarras' o 'texto'")


@app.get("/articulos/pendientes")
def cola(familia: str | None = None, limite: int = Query(50, le=200),
         desplazamiento: int = 0):
    return {"resultados": erp.pendientes(familia, limite, desplazamiento)}


@app.get("/articulos/{id_articulo}")
def ficha(id_articulo: str):
    art = erp.obtener(id_articulo)
    if not art:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "articulo no encontrado")
    return art


# --- Escritura --------------------------------------------------------------

@app.put("/articulos/{id_articulo}/medidas")
def guardar_medidas(id_articulo: str, medidas: Medidas):
    valores = {c: v for c, v in medidas.model_dump().items() if v is not None}
    if not valores:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "no has enviado medidas")

    try:
        cambios = erp.actualizar(id_articulo, valores, USUARIO)
    except erp.ErrorMedida as e:
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_ENTITY, str(e))

    if cambios:
        with local.conexion() as con:
            local.registrar_cambios(con, id_articulo, cambios, USUARIO)

    return {
        "idArticulo": id_articulo,
        "usuario": USUARIO,
        "cambios": {c: {"antes": a, "despues": d} for c, (a, d) in cambios.items()},
        "sinCambios": not cambios,
    }


# --- Fotos ------------------------------------------------------------------

@app.post("/articulos/{id_articulo}/foto", status_code=status.HTTP_201_CREATED)
async def subir_foto(id_articulo: str, fichero: UploadFile = File(...)):
    """Publica la foto en el ERP: fichero en el recurso de red + fila.

    Ese orden importa. Si falla el INSERT queda un fichero suelto que nadie ve;
    al reves queda una referencia apuntando a la nada, que es la averia que ya
    arrastra esta base de datos (17.513 registros rotos de una carga de 2022).

    El nombre es un UUID y no el codigo del articulo. Evita dos problemas
    reales: la clave primaria (Objeto, IdDocObjeto, NombreFichero) rechaza
    repetidos, y hay IdArticulo con espacios, comas y asteriscos.
    """
    art = erp.obtener(id_articulo)
    if not art:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "articulo no encontrado")

    datos = await fichero.read()
    if not datos:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "fichero vacio")
    if len(datos) > config.MAX_FOTO_BYTES:
        raise HTTPException(status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
                            f"maximo {config.MAX_FOTO_BYTES // 1024 // 1024} MB")

    sufijo = Path(fichero.filename or "").suffix.lower() or ".jpg"
    if sufijo not in (".jpg", ".jpeg", ".png", ".webp"):
        raise HTTPException(status.HTTP_415_UNSUPPORTED_MEDIA_TYPE,
                            "formato no admitido")

    nombre = f"{uuid4()}{sufijo}"
    # Principal solo si el articulo no tiene ninguna. Asi una foto nueva nunca
    # destrona a la que ya estaba, y ademas el trigger de borrado del ERP
    # impide dejar un articulo sin principal.
    principal = not erp.tiene_foto(id_articulo)

    with local.conexion() as con:
        cur = con.execute(
            "INSERT INTO fotos (id_articulo, fichero, usuario,"
            " momento, principal, estado) VALUES (?,?,?,?,?,'subiendo')",
            (id_articulo, nombre, USUARIO, local.ahora(), int(principal)))
        fila_id = cur.lastrowid

    def marcar(estado: str, detalle: str = "") -> None:
        with local.conexion() as con:
            con.execute("UPDATE fotos SET estado = ?, detalle = ? WHERE id = ?",
                        (estado, detalle or None, fila_id))

    # 1) El fichero.
    try:
        destino = Path(erp.ruta_imagenes()) / nombre
        destino.write_bytes(datos)
    except erp.ErrorMedida as e:
        marcar("error_config", str(e))
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, str(e))
    except OSError as e:
        marcar("error_fichero", str(e))
        raise HTTPException(
            status.HTTP_503_SERVICE_UNAVAILABLE,
            f"no se pudo escribir en el recurso de imagenes del ERP: {e}")

    # 2) La fila. Si falla, se borra el fichero para no dejar basura.
    try:
        erp.publicar_foto(id_articulo, nombre,
                          f"{art['descrip']} ({id_articulo})",
                          USUARIO, principal)
    except Exception as e:
        try:
            destino.unlink(missing_ok=True)
        except OSError:
            pass
        marcar("error_bd", str(e))
        raise

    marcar("publicada")
    return {"id": fila_id, "fichero": nombre, "principal": principal,
            "usuario": USUARIO, "estado": "publicada"}


@app.get("/articulos/{id_articulo}/fotos")
def fotos_subidas(id_articulo: str):
    with local.conexion() as con:
        filas = con.execute(
            "SELECT id, fichero, usuario, usuario_erp, momento, principal,"
            " estado, detalle FROM fotos WHERE id_articulo = ? ORDER BY id DESC",
            (id_articulo,)).fetchall()
    return {"resultados": [dict(f) for f in filas]}


# --- Historial y deshacer ---------------------------------------------------

@app.get("/historial")
def historial(id_articulo: str | None = None, limite: int = Query(50, le=500)):
    sql = ("SELECT id, id_articulo, campo, valor_antes, valor_despues, usuario,"
           " usuario_erp, momento, deshecho FROM historial")
    params: list = []
    if id_articulo:
        sql += " WHERE id_articulo = ?"
        params.append(id_articulo)
    sql += " ORDER BY id DESC LIMIT ?"
    params.append(limite)
    with local.conexion() as con:
        return {"resultados": [dict(f) for f in con.execute(sql, params)]}


@app.post("/historial/{id_cambio}/deshacer")
def deshacer(id_cambio: int):
    with local.conexion() as con:
        fila = con.execute("SELECT * FROM historial WHERE id = ?",
                           (id_cambio,)).fetchone()
        if not fila:
            raise HTTPException(status.HTTP_404_NOT_FOUND, "cambio no encontrado")
        if fila["deshecho"]:
            raise HTTPException(status.HTTP_409_CONFLICT, "ya estaba deshecho")

        antes = None if fila["valor_antes"] is None else float(fila["valor_antes"])
        try:
            erp.revertir(fila["id_articulo"], fila["campo"], antes, USUARIO)
        except erp.ErrorMedida as e:
            raise HTTPException(status.HTTP_422_UNPROCESSABLE_ENTITY, str(e))

        con.execute("UPDATE historial SET deshecho = 1 WHERE id = ?", (id_cambio,))
        # El propio deshacer se registra, para que el rastro no tenga huecos.
        local.registrar_cambios(
            con, fila["id_articulo"],
            {fila["campo"]: (float(fila["valor_despues"]), antes or 0)},
            USUARIO)

    return {"deshecho": id_cambio, "campo": fila["campo"], "valor": antes}


@app.get("/salud")
def salud():
    """Comprobacion de despliegue: es lo primero que se mira.

    Lo importante son 'escritura' e 'imagenes': las dos cosas que pueden estar
    mal configuradas y no notarse hasta que alguien intenta guardar algo con el
    movil en la mano en medio del almacen.
    """
    info: dict = {"estado": "ok", "campos": list(COLUMNAS_MEDIDA)}

    try:
        motor_lectura().connect().close()
        info["lectura"] = "ok"
    except Exception as e:
        info["estado"] = "degradado"
        info["lectura"] = str(e)[:200]

    try:
        motor_escritura().connect().close()
        info["escritura"] = "ok"
    except SinCredenciales:
        info["estado"] = "degradado"
        info["escritura"] = "sin credenciales configuradas"
    except Exception as e:
        info["estado"] = "degradado"
        info["escritura"] = str(e)[:200]

    try:
        estado_img = config.estado_imagenes(erp.ruta_imagenes())
        estado_img["segunErp"] = erp.ruta_imagenes_erp()
        info["imagenes"] = estado_img
        if not estado_img["escribible"]:
            info["estado"] = "degradado"
    except Exception as e:
        info["estado"] = "degradado"
        info["imagenes"] = {"error": str(e)[:200]}

    return info
