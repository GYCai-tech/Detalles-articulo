"""Exporta el estado actual de medidas y fotos de los articulos del ERP.

Es el punto de partida: saca un CSV con lo que hay y lo que falta, para que
quien recopile los datos (proveedor, hoja de calculo, medicion propia) trabaje
sobre el listado real y devuelva el mismo formato que espera actualizar_medidas.py.

Uso:
    python -m medidas.exportar_pendientes                    # todos los articulos
    python -m medidas.exportar_pendientes --solo-incompletos # los que les falta algo
    python -m medidas.exportar_pendientes --familia 011
"""

import argparse
import csv
from datetime import datetime
from pathlib import Path

from sqlalchemy import text

from .db import motor_lectura, salida_utf8
from .reglas import ARTICULOS_PROTEGIDOS, COLUMNAS_MEDIDA

RAIZ = Path(__file__).resolve().parent.parent

# La descripcion se llama 'Descrip' en la tabla base, no 'Descripcion'.
CONSULTA = f"""
SELECT  a.IdArticulo,
        a.Descrip,
        a.IdFamilia,
        {', '.join('a.' + c for c in COLUMNAS_MEDIDA)},
        CASE WHEN f.idarticulo IS NULL THEN 0 ELSE 1 END AS TieneFoto
FROM    dbo.ARTICULOS a
LEFT JOIN (SELECT DISTINCT idarticulo FROM dbo.persVFotoArticulo) f
       ON f.idarticulo = a.IdArticulo
WHERE   1 = 1
"""

CABECERA = ["IdArticulo", "Descrip", "IdFamilia",
            *COLUMNAS_MEDIDA, "TieneFoto", "Falta"]


def _falta(fila) -> str:
    """AHORA guarda 'sin dato' como 0, no como NULL: hay que comparar contra 0."""
    ausentes = [c for c in COLUMNAS_MEDIDA
                if getattr(fila, c) is None or getattr(fila, c) == 0]
    if not fila.TieneFoto:
        ausentes.append("Foto")
    return "|".join(ausentes)


def main() -> None:
    salida_utf8()
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--familia", help="filtra por IdFamilia (prefijo)")
    p.add_argument("--solo-incompletos", action="store_true",
                   help="excluye los articulos que ya tienen medidas y foto")
    p.add_argument("--salida", type=Path,
                   help="ruta del CSV (por defecto salidas/medidas_AAAAMMDD.csv)")
    args = p.parse_args()

    sql = CONSULTA
    params: dict[str, object] = {}
    if args.familia:
        sql += " AND a.IdFamilia LIKE :fam"
        params["fam"] = args.familia + "%"
    sql += " ORDER BY a.IdArticulo"

    with motor_lectura().connect() as con:
        filas = con.execute(text(sql), params).fetchall()

    destino = args.salida or (
        RAIZ / "salidas" / f"medidas_{datetime.now():%Y%m%d}.csv")
    destino.parent.mkdir(parents=True, exist_ok=True)

    protegidos = escritos = 0
    with destino.open("w", newline="", encoding="utf-8-sig") as fh:
        # utf-8-sig y ';' para que Excel en espanol lo abra bien de un doble clic.
        w = csv.writer(fh, delimiter=";")
        w.writerow(CABECERA)
        for fila in filas:
            if fila.IdArticulo in ARTICULOS_PROTEGIDOS:
                protegidos += 1
                continue
            falta = _falta(fila)
            if args.solo_incompletos and not falta:
                continue
            w.writerow([
                fila.IdArticulo, fila.Descrip, fila.IdFamilia,
                *(getattr(fila, c) or 0 for c in COLUMNAS_MEDIDA),
                fila.TieneFoto, falta,
            ])
            escritos += 1

    print(f"{escritos} articulos -> {destino}")
    if protegidos:
        print(f"{protegidos} articulos protegidos del ERP excluidos "
              f"(el trigger revierte cualquier UPDATE que los toque)")
    print("\nPara rellenar: edita las columnas Peso/Alto/Ancho/Largo y pasa el")
    print("fichero a  python -m medidas.actualizar_medidas <fichero.csv>")


if __name__ == "__main__":
    main()
