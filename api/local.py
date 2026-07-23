"""Estado propio de la API en SQLite: historial de cambios y registro de fotos.

El historial es la pieza importante. Como se escribe directo en dbo.ARTICULOS,
guardar el valor anterior de cada campo es lo unico que hace reversible un
error. Sin esto, un cero de mas en el almacen es permanente.
"""

import sqlite3
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path

from .config import BD_LOCAL

ESQUEMA = """
-- Una fila por campo cambiado, no por peticion: asi el deshacer es por campo.
--
-- 'usuario' es el usuario unico del almacen (API_USUARIO), el mismo que se
-- escribe en ARTICULOS.Usuario. ('usuario_erp' quedo de una version anterior
-- que permitia firmar con otro nombre; la columna se conserva por las filas
-- antiguas.)
CREATE TABLE IF NOT EXISTS historial (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    id_articulo  TEXT NOT NULL,
    campo        TEXT NOT NULL,
    valor_antes  TEXT,
    valor_despues TEXT NOT NULL,
    usuario      TEXT NOT NULL,
    usuario_erp  TEXT,
    momento      TEXT NOT NULL,
    deshecho     INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS ix_historial_articulo ON historial(id_articulo);
CREATE INDEX IF NOT EXISTS ix_historial_momento  ON historial(momento);

-- Registro de fotos subidas. 'estado' distingue si llego a publicarse en el
-- ERP o se quedo a medias, que es justo lo que nadie registro en 2022 y por
-- eso hay 17.513 referencias apuntando a ficheros inexistentes.
CREATE TABLE IF NOT EXISTS fotos (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    id_articulo  TEXT NOT NULL,
    fichero      TEXT NOT NULL,
    usuario      TEXT NOT NULL,
    usuario_erp  TEXT,
    momento      TEXT NOT NULL,
    principal    INTEGER NOT NULL DEFAULT 0,
    estado       TEXT NOT NULL DEFAULT 'pendiente',
    detalle      TEXT
);
CREATE INDEX IF NOT EXISTS ix_fotos_articulo ON fotos(id_articulo);
"""

# Columnas anadidas despues de la primera version. SQLite no tiene
# "ADD COLUMN IF NOT EXISTS", asi que se comprueba a mano.
MIGRACIONES = [
    ("historial", "usuario_erp", "TEXT"),
    ("fotos", "usuario_erp", "TEXT"),
    ("fotos", "principal", "INTEGER NOT NULL DEFAULT 0"),
    ("fotos", "detalle", "TEXT"),
]


def ahora() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


@contextmanager
def conexion(ruta: Path = BD_LOCAL):
    con = sqlite3.connect(ruta)
    con.row_factory = sqlite3.Row
    con.execute("PRAGMA foreign_keys = ON")
    try:
        yield con
        con.commit()
    except Exception:
        con.rollback()
        raise
    finally:
        con.close()


def inicializar() -> None:
    with conexion() as con:
        con.executescript(ESQUEMA)
        for tabla, columna, tipo in MIGRACIONES:
            existentes = {f["name"] for f in
                          con.execute(f"PRAGMA table_info({tabla})")}
            if existentes and columna not in existentes:
                con.execute(f"ALTER TABLE {tabla} ADD COLUMN {columna} {tipo}")


def registrar_cambios(con: sqlite3.Connection, id_articulo: str,
                      cambios: dict, usuario: str,
                      usuario_erp: str | None = None) -> None:
    """cambios = {campo: (antes, despues)}"""
    momento = ahora()
    con.executemany(
        "INSERT INTO historial (id_articulo, campo, valor_antes, valor_despues,"
        " usuario, usuario_erp, momento) VALUES (?,?,?,?,?,?,?)",
        [(id_articulo, campo,
          None if antes is None else str(antes), str(despues),
          usuario, usuario_erp, momento)
         for campo, (antes, despues) in cambios.items()],
    )
