"""Conexion al ERP GOMEZYCRESPO (SQL Server).

Dos motores separados a proposito:
  - motor_lectura()   -> usuario de solo lectura, para exportar e inspeccionar
  - motor_escritura() -> usuario con permiso de UPDATE, obligatorio para --aplicar

Que sean credenciales distintas es intencionado: hace imposible que un script de
exploracion escriba por accidente.
"""

import os
import sys
from pathlib import Path

from dotenv import load_dotenv
from sqlalchemy import create_engine
from sqlalchemy.engine import Engine

RAIZ = Path(__file__).resolve().parent.parent
load_dotenv(RAIZ / ".env", override=True)

DRIVER = "ODBC+Driver+18+for+SQL+Server"


class SinCredenciales(RuntimeError):
    """Faltan credenciales en .env.

    Excepcion propia y no SystemExit: estos motores los usa tanto un script de
    consola (donde salir esta bien) como la API (donde salir del proceso por
    una peticion mal configurada seria absurdo). Cada uno decide que hacer.
    """


def _url(usuario: str, clave: str) -> str:
    from urllib.parse import quote_plus

    host = os.getenv("SQLSERVER_HOST", "SRVAHORA")
    puerto = os.getenv("SQLSERVER_PORT", "1433")
    base = os.getenv("SQLSERVER_DB", "GOMEZYCRESPO")
    return (
        f"mssql+pyodbc://{quote_plus(usuario)}:{quote_plus(clave)}@{host}:{puerto}/{base}"
        f"?driver={DRIVER}&TrustServerCertificate=yes"
    )


def motor_lectura() -> Engine:
    usuario = os.getenv("SQLSERVER_USER")
    clave = os.getenv("SQLSERVER_PASS")
    if not usuario or not clave:
        raise SinCredenciales(
            "Faltan SQLSERVER_USER / SQLSERVER_PASS en .env "
            "(copia .env.example a .env y rellenalo)."
        )
    return create_engine(_url(usuario, clave), fast_executemany=True)


def motor_escritura() -> Engine:
    usuario = os.getenv("SQLSERVER_WRITE_USER")
    clave = os.getenv("SQLSERVER_WRITE_PASS")
    if not usuario or not clave:
        raise SinCredenciales(
            "No hay credenciales de escritura configuradas.\n"
            "  SQLSERVER_WRITE_USER / SQLSERVER_WRITE_PASS estan vacias en .env.\n"
            "  El usuario de lectura (gestor_incidencias) es db_datareader y no puede\n"
            "  escribir: eso es deliberado, no lo sortees reutilizandolo aqui.\n"
            "  Pide al administrador de SQL un login con UPDATE sobre dbo.ARTICULOS."
        )
    return create_engine(_url(usuario, clave), fast_executemany=True)


def salida_utf8() -> None:
    """La consola de Windows es cp1252; los textos del ERP no."""
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
