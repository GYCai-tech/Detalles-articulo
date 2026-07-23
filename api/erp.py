"""Acceso a dbo.ARTICULOS. Es el unico modulo con credencial de escritura.

Las reglas verificadas contra el trigger Articulos_UTrig viven en
medidas/reglas.py y se importan de ahi: una sola fuente de verdad, compartida
con los scripts de linea de comandos.
"""

from decimal import Decimal

from sqlalchemy import text

from medidas.db import motor_escritura, motor_lectura
from medidas.reglas import (ARTICULOS_PROTEGIDOS, COL_FECHA, COL_USUARIO,
                            COLUMNAS_MEDIDA, validar)

from .config import MONTAJE_IMAGENES

# Codigos de barras basura que existen en produccion y que apuntan a decenas
# de articulos distintos. Escanear uno no identifica nada.
CODBARRAS_INVALIDOS = {"0", "00", "000", "11111", "111111", "1111111111111111"}

_COLS = ", ".join(f"a.{c}" for c in COLUMNAS_MEDIDA)

_SELECT_BASE = f"""
SELECT  a.IdArticulo, a.Descrip, a.IdFamilia, a.CodBarras, {_COLS},
        CASE WHEN f.idarticulo IS NULL THEN 0 ELSE 1 END AS TieneFoto
FROM    dbo.ARTICULOS a
LEFT JOIN (SELECT DISTINCT idarticulo FROM dbo.persVFotoArticulo) f
       ON f.idarticulo = a.IdArticulo
"""


def _a_dict(fila) -> dict:
    return {
        "idArticulo": fila.IdArticulo,
        "descrip": fila.Descrip,
        "idFamilia": fila.IdFamilia,
        "codBarras": (fila.CodBarras or "").strip() or None,
        "tieneFoto": bool(fila.TieneFoto),
        "medidas": {c: (float(getattr(fila, c)) if getattr(fila, c) else None)
                    for c in COLUMNAS_MEDIDA},
        "protegido": fila.IdArticulo in ARTICULOS_PROTEGIDOS,
    }


def obtener(id_articulo: str) -> dict | None:
    with motor_lectura().connect() as con:
        fila = con.execute(text(_SELECT_BASE + " WHERE a.IdArticulo = :id"),
                           {"id": id_articulo}).first()
    return _a_dict(fila) if fila else None


def buscar_por_codbarras(codigo: str) -> list[dict]:
    """Devuelve lista: un mismo codigo puede apuntar a varios articulos."""
    codigo = (codigo or "").strip()
    if not codigo or codigo in CODBARRAS_INVALIDOS:
        return []
    with motor_lectura().connect() as con:
        filas = con.execute(
            text(_SELECT_BASE + " WHERE LTRIM(RTRIM(a.CodBarras)) = :cb"
                                " ORDER BY a.IdArticulo"),
            {"cb": codigo}).fetchall()
    return [_a_dict(f) for f in filas]


def buscar_por_texto(termino: str, limite: int = 30) -> list[dict]:
    """Respaldo para cuando el articulo no tiene etiqueta legible."""
    termino = (termino or "").strip()
    if len(termino) < 2:
        return []
    with motor_lectura().connect() as con:
        filas = con.execute(
            text(_SELECT_BASE +
                 " WHERE a.IdArticulo LIKE :t OR a.Descrip LIKE :t"
                 " ORDER BY a.IdArticulo OFFSET 0 ROWS FETCH NEXT :n ROWS ONLY"),
            {"t": f"%{termino}%", "n": limite}).fetchall()
    return [_a_dict(f) for f in filas]


def pendientes(familia: str | None = None, limite: int = 50,
               desplazamiento: int = 0) -> list[dict]:
    """Cola de trabajo: articulos a los que les falta alguna medida.

    Nota: hay 25.513 articulos activos y a 22.611 les falta el largo. Esta
    lista sin filtrar no es un plan de trabajo realista; se espera que la app
    filtre por familia o que se acote al catalogo comercial (~600 productos).
    """
    falta = " OR ".join(f"(a.{c} IS NULL OR a.{c} = 0)" for c in COLUMNAS_MEDIDA)
    sql = _SELECT_BASE + f" WHERE ({falta})"
    params: dict = {"n": limite, "off": desplazamiento}
    if familia:
        sql += " AND a.IdFamilia LIKE :fam"
        params["fam"] = familia + "%"
    sql += (" ORDER BY a.IdArticulo"
            " OFFSET :off ROWS FETCH NEXT :n ROWS ONLY")
    with motor_lectura().connect() as con:
        filas = con.execute(text(sql), params).fetchall()
    return [_a_dict(f) for f in filas]


class ErrorMedida(ValueError):
    """Valor rechazado antes de llegar a la base de datos."""


def actualizar(id_articulo: str, valores: dict[str, float],
               usuario: str) -> dict[str, tuple]:
    """Escribe las medidas y devuelve {campo: (antes, despues)}.

    Devolver el valor anterior no es un detalle: es lo que permite deshacer.
    """
    if id_articulo in ARTICULOS_PROTEGIDOS:
        raise ErrorMedida(
            f"'{id_articulo}' es un articulo protegido del ERP: el trigger "
            f"Articulos_UTrig revierte cualquier UPDATE que lo toque.")

    limpios: dict[str, Decimal] = {}
    for campo, valor in valores.items():
        if campo not in COLUMNAS_MEDIDA:
            raise ErrorMedida(f"campo no permitido: {campo}")
        if valor is None:
            continue
        motivo = validar(campo, float(valor))
        if motivo:
            raise ErrorMedida(motivo)
        limpios[campo] = Decimal(str(valor))

    if not limpios:
        return {}

    actual = obtener(id_articulo)
    if actual is None:
        raise ErrorMedida(f"el articulo '{id_articulo}' no existe")

    # Solo escribimos lo que de verdad cambia: evita UPDATEs inutiles y deja
    # el historial limpio de ruido.
    cambios = {c: (actual["medidas"][c], float(v)) for c, v in limpios.items()
               if actual["medidas"][c] != float(v)}
    if not cambios:
        return {}

    asigna = ", ".join(f"{c} = :{c}" for c in cambios)
    marcas = ", ".join(f":_p{i}" for i in range(len(ARTICULOS_PROTEGIDOS)))
    # SET NOCOUNT ON + @@ROWCOUNT en vez del rowcount del driver: los triggers
    # de ARTICULOS emiten sus propios contadores y pyodbc devuelve el primero
    # que llega (0), aunque el UPDATE haya escrito. Comprobado contra el ERP
    # real el 23/07/2026.
    sql = (f"SET NOCOUNT ON;"
           f" UPDATE dbo.ARTICULOS SET {asigna}, {COL_USUARIO} = :_usuario,"
           f" {COL_FECHA} = GETDATE()"
           f" WHERE IdArticulo = :_id AND IdArticulo NOT IN ({marcas});"
           f" SELECT @@ROWCOUNT AS n")

    with motor_escritura().begin() as con:
        n = con.execute(text(sql), {
            **{c: limpios[c] for c in cambios},
            "_usuario": usuario[:50],
            "_id": id_articulo,
            **{f"_p{i}": a for i, a in enumerate(ARTICULOS_PROTEGIDOS)},
        }).scalar()
        if n != 1:
            raise ErrorMedida(
                f"el UPDATE afecto a {n} filas, se esperaba 1")

    return cambios


def ruta_imagenes_erp() -> str:
    """Lo que el ERP dice que es su carpeta de imagenes (ruta UNC de Windows).

    Vive en Ceesi_configuracion con PARAMETRO='PATHIMAGENES'. Leerla en vez de
    fijarla evita que la app siga escribiendo en la carpeta antigua el dia que
    alguien la cambie.
    """
    with motor_lectura().connect() as con:
        valor = con.execute(text(
            "SELECT Valor FROM dbo.Ceesi_configuracion"
            " WHERE PARAMETRO = 'PATHIMAGENES'")).scalar()
    if not valor:
        raise ErrorMedida("PATHIMAGENES no esta configurado en Ceesi_configuracion")
    return valor.strip()


def ruta_imagenes() -> str:
    """Donde escribir las fotos DESDE ESTA MAQUINA.

    En Windows coincide con lo que dice el ERP. En Linux hay que usar el punto
    de montaje CIFS, porque una UNC no se puede abrir.

    Se comprueba ademas que el montaje siga apuntando al mismo recurso que el
    ERP tiene configurado. Si alguien cambia PATHIMAGENES y nadie remonta, la
    API escribiria en la carpeta antigua mientras el ERP busca en la nueva: las
    fotos se subirian "con exito" y no se verian. Preferible fallar.
    """
    del_erp = ruta_imagenes_erp()
    if not MONTAJE_IMAGENES:
        return del_erp

    ultimo = del_erp.replace("\\", "/").rstrip("/").rsplit("/", 1)[-1].lower()
    if ultimo and ultimo not in MONTAJE_IMAGENES.replace("\\", "/").lower():
        raise ErrorMedida(
            f"el montaje local ({MONTAJE_IMAGENES}) no parece corresponder con "
            f"la carpeta que el ERP tiene configurada ({del_erp}). "
            f"Revisa API_MONTAJE_IMAGENES antes de subir fotos.")
    return MONTAJE_IMAGENES


def _iddoc(id_articulo: str) -> int:
    """Objetos_Imagenes enlaza por ARTICULOS.IdDoc, no por IdArticulo."""
    with motor_lectura().connect() as con:
        iddoc = con.execute(
            text("SELECT IdDoc FROM dbo.ARTICULOS WHERE IdArticulo = :id"),
            {"id": id_articulo}).scalar()
    if iddoc is None:
        raise ErrorMedida(f"el articulo '{id_articulo}' no existe")
    return int(iddoc)


def tiene_foto(id_articulo: str) -> bool:
    with motor_lectura().connect() as con:
        n = con.execute(text(
            "SELECT COUNT(*) FROM dbo.Objetos_Imagenes"
            " WHERE Objeto = 'Articulo' AND IdDocObjeto = :d"),
            {"d": _iddoc(id_articulo)}).scalar()
    return bool(n)


def publicar_foto(id_articulo: str, nombre_fichero: str, descripcion: str,
                  usuario: str, principal: bool) -> None:
    """Inserta la referencia de la foto. El fichero YA debe estar en disco.

    Objetos_Imagenes no tiene trigger de INSERT ni de UPDATE (el unico es
    Objetos_Imagenes_DTrig, FOR DELETE), asi que esto no dispara nada.
    IdDoc es identity y no se envia.
    """
    with motor_escritura().begin() as con:
        con.execute(text("""
            INSERT INTO dbo.Objetos_Imagenes
                   (Objeto, NombreFichero, IdDocObjeto, Descrip,
                    Principal, Orden, IdClasificacion, InsertUpdate, Usuario,
                    FechaInsertUpdate)
            VALUES ('Articulo', :fichero, :iddoc, :descrip,
                    :principal, 0, 0, 0, :usuario, GETDATE())
        """), {
            "fichero": nombre_fichero,
            "iddoc": _iddoc(id_articulo),
            "descrip": descripcion[:255],
            "principal": 1 if principal else 0,
            "usuario": usuario[:50],
        })


def revertir(id_articulo: str, campo: str, valor_antes: float | None,
             usuario: str) -> None:
    """Deshace un cambio del historial devolviendo el campo a su valor previo.

    Un valor previo nulo se escribe como 0, que es como AHORA representa
    'sin dato' (no usa NULL).
    """
    if campo not in COLUMNAS_MEDIDA:
        raise ErrorMedida(f"campo no permitido: {campo}")
    destino = Decimal("0") if valor_antes is None else Decimal(str(valor_antes))
    marcas = ", ".join(f":_p{i}" for i in range(len(ARTICULOS_PROTEGIDOS)))
    with motor_escritura().begin() as con:
        con.execute(text(
            f"UPDATE dbo.ARTICULOS SET {campo} = :v, {COL_USUARIO} = :_usuario,"
            f" {COL_FECHA} = GETDATE()"
            f" WHERE IdArticulo = :_id AND IdArticulo NOT IN ({marcas})"), {
            "v": destino, "_usuario": usuario[:50], "_id": id_articulo,
            **{f"_p{i}": a for i, a in enumerate(ARTICULOS_PROTEGIDOS)},
        })
