"""Actualiza Peso / Alto / Ancho / Largo en dbo.ARTICULOS desde un CSV.

Por defecto NO escribe: ensena el diff y termina. Hay que pasar --aplicar
explicitamente, y ademas tener credenciales de escritura en .env.

Uso:
    python -m medidas.actualizar_medidas datos/medidas.csv            # dry-run
    python -m medidas.actualizar_medidas datos/medidas.csv --aplicar  # escribe

Formato del CSV (separador ';', el mismo que produce exportar_pendientes.py):
    IdArticulo;Peso;Alto;Ancho;Largo
Un 0 o una celda vacia significan "no tengo el dato" y se dejan como estan.
"""

import argparse
import csv
import os
import sys
from decimal import Decimal, InvalidOperation
from pathlib import Path

from sqlalchemy import text

from .db import SinCredenciales, motor_escritura, motor_lectura, salida_utf8
from .reglas import (ARTICULOS_PROTEGIDOS, COL_FECHA, COL_USUARIO,
                     COLUMNAS_MEDIDA, SIN_DATO, validar)

# El trigger es por statement, no por fila: un ROLLBACK revierte todo el lote.
# Lotes pequenos limitan cuanto trabajo se pierde si uno falla.
TAM_LOTE = 200


def _decimal(bruto: str) -> Decimal | None:
    """Acepta '1,5' y '1.5'. Devuelve None si la celda esta vacia."""
    bruto = (bruto or "").strip().replace(",", ".")
    if not bruto:
        return None
    try:
        return Decimal(bruto)
    except InvalidOperation:
        raise ValueError(f"valor no numerico: {bruto!r}")


def leer_csv(ruta: Path) -> list[dict]:
    with ruta.open(encoding="utf-8-sig", newline="") as fh:
        muestra = fh.read(4096)
        fh.seek(0)
        try:
            dialecto = csv.Sniffer().sniff(muestra, delimiters=";,\t")
        except csv.Error:
            dialecto = csv.excel
            dialecto.delimiter = ";"
        lector = csv.DictReader(fh, dialect=dialecto)
        if not lector.fieldnames or "IdArticulo" not in lector.fieldnames:
            raise SystemExit(f"{ruta}: falta la columna obligatoria 'IdArticulo'")
        presentes = [c for c in COLUMNAS_MEDIDA if c in lector.fieldnames]
        if not presentes:
            raise SystemExit(
                f"{ruta}: no hay ninguna columna de medida "
                f"({', '.join(COLUMNAS_MEDIDA)})")
        return [dict(f) for f in lector]


def preparar(filas: list[dict], actuales: dict[str, dict]) -> tuple[list, list]:
    """Devuelve (cambios, incidencias). Un cambio solo entra si el valor difiere."""
    cambios, incidencias = [], []

    for n, fila in enumerate(filas, start=2):  # 2 = primera linea de datos
        articulo = (fila.get("IdArticulo") or "").strip()
        if not articulo:
            incidencias.append((n, "", "sin IdArticulo"))
            continue
        if articulo in ARTICULOS_PROTEGIDOS:
            incidencias.append((n, articulo, "articulo protegido del ERP, se omite"))
            continue
        if articulo not in actuales:
            incidencias.append((n, articulo, "no existe en dbo.ARTICULOS"))
            continue

        nuevos = {}
        for col in COLUMNAS_MEDIDA:
            if col not in fila:
                continue
            try:
                valor = _decimal(fila[col])
            except ValueError as e:
                incidencias.append((n, articulo, str(e)))
                continue
            if valor is None or valor == SIN_DATO:
                continue  # "no tengo el dato": no se pisa lo que ya hay
            motivo = validar(col, float(valor))
            if motivo:
                incidencias.append((n, articulo, motivo))
                continue
            if actuales[articulo][col] is not None and \
                    Decimal(actuales[articulo][col]) == valor:
                continue  # ya vale eso, no generamos un UPDATE inutil
            nuevos[col] = valor

        if nuevos:
            cambios.append((articulo, nuevos, actuales[articulo]))

    return cambios, incidencias


def mostrar(cambios: list, incidencias: list, aplicar: bool) -> None:
    if incidencias:
        print(f"\n--- {len(incidencias)} incidencias ---")
        for linea, articulo, motivo in incidencias[:40]:
            print(f"  linea {linea:<5} {articulo:<20} {motivo}")
        if len(incidencias) > 40:
            print(f"  ... y {len(incidencias) - 40} mas")

    print(f"\n--- {len(cambios)} articulos a actualizar ---")
    for articulo, nuevos, antes in cambios[:40]:
        detalle = ", ".join(
            f"{c}: {_corto(antes[c])} -> {_corto(v)}" for c, v in nuevos.items())
        print(f"  {articulo:<20} {detalle}")
    if len(cambios) > 40:
        print(f"  ... y {len(cambios) - 40} mas")

    if not aplicar:
        print("\nDRY-RUN: no se ha escrito nada. Anade --aplicar para ejecutar.")


def _corto(v) -> str:
    """38,14 decimales son ilegibles en un diff."""
    if v is None:
        return "-"
    return f"{Decimal(v).normalize():f}"


def escribir(cambios: list, usuario: str) -> tuple[int, int]:
    try:
        motor = motor_escritura()
    except SinCredenciales as e:
        raise SystemExit(str(e))
    hechos = fallidos = 0

    with motor.connect() as con:
        for i in range(0, len(cambios), TAM_LOTE):
            lote = cambios[i:i + TAM_LOTE]
            trans = con.begin()
            try:
                for articulo, nuevos, _ in lote:
                    asigna = ", ".join(f"{c} = :{c}" for c in nuevos)
                    # El NOT IN es un cinturon de seguridad redundante: preparar()
                    # ya los descarto. Esta aqui porque el precio de colar uno es
                    # perder el lote entero por ROLLBACK del trigger.
                    marcas = ", ".join(f":_p{i}" for i in range(len(ARTICULOS_PROTEGIDOS)))
                    sql = (
                        f"UPDATE dbo.ARTICULOS "
                        f"SET {asigna}, {COL_USUARIO} = :_usuario, "
                        f"    {COL_FECHA} = GETDATE() "
                        f"WHERE IdArticulo = :_articulo "
                        f"  AND IdArticulo NOT IN ({marcas})"
                    )
                    con.execute(text(sql), {
                        **nuevos,
                        "_usuario": usuario,
                        "_articulo": articulo,
                        **{f"_p{i}": a for i, a in enumerate(ARTICULOS_PROTEGIDOS)},
                    })
                trans.commit()
                hechos += len(lote)
                print(f"  lote {i // TAM_LOTE + 1}: {len(lote)} actualizados")
            except Exception as e:
                trans.rollback()
                fallidos += len(lote)
                print(f"  lote {i // TAM_LOTE + 1}: REVERTIDO -> {e}",
                      file=sys.stderr)
                # 3609 = "The transaction ended in the trigger". Es una de las
                # validaciones de Articulos_UTrig, no un fallo de conexion.
                if "3609" in str(e):
                    print("     (lo ha abortado un trigger del ERP, "
                          "no un error de red)", file=sys.stderr)

    return hechos, fallidos


def main() -> None:
    salida_utf8()
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("csv", type=Path, help="CSV con IdArticulo y columnas de medida")
    p.add_argument("--aplicar", action="store_true",
                   help="escribe de verdad (por defecto solo ensena el diff)")
    args = p.parse_args()

    if not args.csv.exists():
        raise SystemExit(f"no existe: {args.csv}")

    filas = leer_csv(args.csv)
    print(f"{len(filas)} filas leidas de {args.csv}")

    # 25.500 articulos caben de sobra en memoria y evitan un IN gigante.
    columnas = ", ".join(COLUMNAS_MEDIDA)
    with motor_lectura().connect() as con:
        actuales = {
            r.IdArticulo: {c: getattr(r, c) for c in COLUMNAS_MEDIDA}
            for r in con.execute(text(
                f"SELECT IdArticulo, {columnas} FROM dbo.ARTICULOS"))
        }

    cambios, incidencias = preparar(filas, actuales)
    mostrar(cambios, incidencias, args.aplicar)

    if not args.aplicar or not cambios:
        return

    usuario = os.getenv("ERP_USUARIO_AUDITORIA", "medidas_bot")
    print(f"\nEscribiendo como Usuario='{usuario}' en lotes de {TAM_LOTE}...")
    hechos, fallidos = escribir(cambios, usuario)
    print(f"\n{hechos} actualizados, {fallidos} en lotes revertidos.")


if __name__ == "__main__":
    main()
