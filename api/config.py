"""Configuracion de la API. Todo por variables de entorno / .env."""

import os
from pathlib import Path

from dotenv import load_dotenv

RAIZ = Path(__file__).resolve().parent.parent
load_dotenv(RAIZ / ".env", override=True)

# Base local de la API: usuarios, historial de cambios y cola de fotos.
# SQLite basta: es una herramienta interna en LAN, no un servicio publico.
BD_LOCAL = Path(os.getenv("API_BD_LOCAL", RAIZ / "api" / "estado.sqlite"))

# La app no tiene login: en el almacen se usa un unico usuario y la API solo
# es alcanzable en LAN. Este nombre firma todo en ARTICULOS.Usuario y en
# Objetos_Imagenes.Usuario.
USUARIO = os.getenv("API_USUARIO", "ricardo").strip() or "ricardo"

# Donde estan montadas las imagenes del ERP EN ESTA MAQUINA.
#
# El ERP guarda la ruta como UNC de Windows (\\10.0.0.2\goyco\...\IMAGENES) en
# Ceesi_configuracion. Eso solo se puede abrir tal cual desde Windows. La API
# vive en Linux (10.0.0.12), donde el recurso tiene que estar montado por CIFS
# y se ve como un directorio normal.
#
# Si esta variable esta puesta, se usa en lugar de la UNC. Si no, se usa la UNC
# tal cual, que es lo correcto cuando la API corre en Windows.
MONTAJE_IMAGENES = os.getenv("API_MONTAJE_IMAGENES", "").strip()

MAX_FOTO_BYTES = int(os.getenv("API_MAX_FOTO_BYTES", 12 * 1024 * 1024))


def comprobar() -> None:
    """Falla al arrancar, no en la primera peticion."""
    BD_LOCAL.parent.mkdir(parents=True, exist_ok=True)

    if os.name != "nt" and not MONTAJE_IMAGENES:
        raise SystemExit(
            "API_MONTAJE_IMAGENES no esta configurada.\n"
            "  El ERP guarda la carpeta de imagenes como ruta UNC de Windows,\n"
            "  que en Linux no se puede abrir. Monta el recurso por CIFS e\n"
            "  indica aqui el punto de montaje (ver despliegue/README.md).\n"
            "  Sin esto las medidas funcionarian pero las fotos fallarian una a\n"
            "  una, que es peor que no arrancar."
        )


def estado_imagenes(ruta: str) -> dict:
    """Diagnostico para /salud: que el fallo se vea antes de la primera foto."""
    carpeta = Path(ruta)
    existe = carpeta.is_dir()
    escribible = False
    if existe:
        prueba = carpeta / f".prueba_api_{os.getpid()}"
        try:
            prueba.write_bytes(b"")
            prueba.unlink()
            escribible = True
        except OSError:
            pass
    return {"ruta": ruta, "existe": existe, "escribible": escribible}
