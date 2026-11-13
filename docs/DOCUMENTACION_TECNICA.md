# Documentación técnica — Detalles de artículos

Documento de relevo para quien mantenga la aplicación. Para el uso en el almacén y las incidencias del día
a día ver [GUIA_SOPORTE.md](GUIA_SOPORTE.md). El `README.md` del repositorio tiene el detalle completo de
la auditoría del ERP (triggers, fotos, unidades); este documento lo resume y lo pone al día.

---

## 1. Resumen

| Aspecto | Detalle |
|---|---|
| Qué hace | Completar **peso, medidas, fotos, código de fabricante y EAN** del maestro de artículos del ERP midiendo con una PDA o móvil en el almacén |
| Repositorio | `GYCai-tech/Detalles-articulo` (rama `main`) |
| Componentes | `api/` FastAPI (único punto con escritura) · `medidas/` reglas + scripts CSV · `android/` app Kotlin + Compose |
| Datos | ERP SQL Server `GOMEZYCRESPO` (`10.0.0.6:1433`): **lee y escribe** en `dbo.ARTICULOS`, `dbo.Articulos_CodBarras` y `dbo.Objetos_Imagenes`; ficheros de foto en `\\10.0.0.2\goyco\AHORA\Documentos\IMAGENES` |
| Estado propio | SQLite (`historial` de cambios y `fotos`) en el volumen Docker `estado` |
| Ejecución | Docker, contenedor `detalles-api`, puerto **8000** |
| Producción | `/home/goyco/apps/detalles-artículo` → `http://10.0.0.12:8000` (`/salud` responde `ok` en lectura, escritura e imágenes) |
| Autenticación | **Ninguna**. Todo se firma en el ERP con `API_USUARIO` |
| Tests / CI | No hay. Despliegue manual |

> **Diferencia con el resto de apps:** esta **escribe en el ERP de producción**. Cualquier cambio en las
> reglas debe revisarse contra los triggers de `ARTICULOS` antes de desplegar.

## 2. Arquitectura

```
PDA Honeywell / móvil  ──HTTP (LAN)──>  API FastAPI (10.0.0.12:8000)  ──>  SQL Server 10.0.0.6
  escanea EAN, mide, foto                 valida, escribe, registra           ARTICULOS, Objetos_Imagenes
                                          └─> /imagenes (CIFS montado en el host) ──> \\10.0.0.2\goyco\...
```

La app **no** habla con SQL Server: una credencial dentro de un APK es pública.

## 3. API

| Endpoint | Para qué |
|---|---|
| `GET /articulos/buscar?codbarras=` | Escáner. **Devuelve lista** (62 artículos comparten `CodBarras = '0'`) |
| `GET /articulos/buscar?texto=` | Búsqueda por texto |
| `GET /articulos/pendientes?familia=&limite=&desplazamiento=` | Cola de artículos sin medidas |
| `GET /articulos/{id}` · `GET /articulos/{id}/imagen` | Ficha y foto principal |
| `PUT /articulos/{id}/medidas` | Escribe `Peso/Alto/Ancho/Largo/Profundo`; guarda el valor anterior en `historial` |
| `PUT /articulos/{id}/fabricante` | Escribe `ARTICULOS.CodigoFabricante` (con historial) |
| `PUT /articulos/{id}/codbarras` | Alta en `Articulos_CodBarras`. 409 si el código ya es de otro artículo |
| `POST /articulos/{id}/foto?reemplazar=` | **Publica** la foto: fichero (nombre UUID) y luego fila en `Objetos_Imagenes` |
| `GET /articulos/{id}/fotos` | Fotos subidas desde la API y su estado |
| `GET /historial` · `POST /historial/{id}/deshacer` | Auditoría y deshacer (el deshacer también queda registrado) |
| `GET /salud` | Diagnóstico: lectura, escritura y carpeta de imágenes (`existe`, `escribible`) |

Documentación interactiva en `http://10.0.0.12:8000/docs`.

### 3.1 Reglas de escritura (`medidas/reglas.py`, fuente única)

- Solo se escriben `COLUMNAS_MEDIDA` + `Usuario` + `FechaInsertUpdate`. **Prohibido**: `CantidadBulto`,
  `IdEmbalaje`, `IdFamilia`, `IdArticulo` (el trigger recalcula `CantidadBulto` al cambiar `IdEmbalaje`).
- `GEN. PARTE`, `PARTE_PRIMASALIDA` y `GEN. TRABAJO EXTERNO` están **blindados**: `Articulos_UTrig` hace
  `ROLLBACK` del statement entero. Se excluyen en el `WHERE` y se escribe en lotes de 200.
- Cotas de cordura (`LIMITES`): peso 0,0001–15 000 kg; medidas 0,01–10 000. No detectan errores de unidad.
- `erp.actualizar` usa `SET NOCOUNT ON` + `@@ROWCOUNT` porque los triggers falsean el `rowcount` del driver.

### 3.2 Fotos

- Primero el fichero, después la fila; si falla la fila se borra el fichero.
- Sin `reemplazar`, la foto solo es principal si el artículo no tenía ninguna. Con `reemplazar=true` pasa
  a principal y la anterior queda como secundaria (nunca se borra nada del ERP).
- Tamaño máximo `API_MAX_FOTO_BYTES` (12 MB); formatos jpg, png, webp. La app reduce a 1600 px / calidad 85.
- La ruta se lee de `Ceesi_configuracion.PATHIMAGENES`; en Linux se usa el montaje `API_MONTAJE_IMAGENES`.
  Sin esa variable la API **no arranca** en Linux (mejor que fallar foto a foto).

## 4. App Android

- Kotlin + Compose, `minSdk 26`. Compilar: `cd android && gradlew.bat :app:assembleDebug`;
  instalar: `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
- **Servidor fijo en el código**: `URL_SERVIDOR = "http://10.0.0.12:8000"` en `ModeloPrincipal.kt`.
  (El README menciona una pantalla de Ajustes que no existe en `main`.) Cambiar de servidor = recompilar.
- Campos del formulario: `Peso (kg)`, `Alto`, `Ancho` y `Profundo` rotulado **«Largo / fondo»** (cm),
  más *Código de fabricante*. `Largo` (tubos y barras) no se edita desde la app.
- Escáner Honeywell sin SDK: broadcast con acción `es.gyc.detalles.SCAN`, código en el extra `data`
  (configuración en la guía). Respaldo con la cámara (`play-services-code-scanner`).
- Distingue `503 causa=sin_credenciales` («NO se ha guardado») de un fallo de red.
- Sin cola offline: sin WiFi la medida se pierde.

## 5. Scripts de carga masiva

```bash
python -m medidas.exportar_pendientes --solo-incompletos            # CSV en salidas/ (; y UTF-8 BOM)
python -m medidas.actualizar_medidas salidas/medidas_AAAAMMDD.csv   # solo enseña el diff
python -m medidas.actualizar_medidas salidas/medidas_AAAAMMDD.csv --aplicar
```

Peso en kg, dimensiones en cm; `0` o vacío no pisa lo existente. `--aplicar` exige
`SQLSERVER_WRITE_USER/PASS`.

## 6. Configuración y despliegue

| Variable | Uso |
|---|---|
| `SQLSERVER_HOST` | **Por IP** (`10.0.0.6`): dentro de Docker no resuelve `SRVAHORA` |
| `SQLSERVER_USER/PASS` | Lectura (`gestor_incidencias`, `db_datareader`) |
| `SQLSERVER_WRITE_USER/PASS` | Escritura (`detalles_escritura`) |
| `API_USUARIO` | Nombre con el que se firma en el ERP (por defecto `ricardo`) |
| `ERP_USUARIO_AUDITORIA` | Firma de los scripts CSV |

Requisitos del host: recurso `//10.0.0.2/goyco` montado por CIFS en `/mnt/goyco-imagenes` con
**`uid=1000`** (el usuario del contenedor). Detalle en `despliegue/README.md`.

```bash
cd /home/goyco/apps/detalles-artículo
git pull && docker compose up -d --build
curl -s http://localhost:8000/salud           # "estado": "ok"
```

Copia del historial: `docker run --rm -v <proyecto>_estado:/d alpine cat /d/estado.sqlite > estado.sqlite`.

## 7. Trampas conocidas

- **`Largo` ≠ `Profundo`**: el «largo» del catálogo va en `Profundo`; `Largo` es para productos lineales.
- **Unidades mezcladas**: catálogo en cm, materia prima en mm, en las mismas columnas.
- AHORA guarda «sin dato» como `0`, no `NULL`: medir cobertura con `<> 0`.
- El 88 % de los registros de `Objetos_Imagenes` apunta a ficheros inexistentes (carga de 2022):
  `persVFotoArticulo` da una cobertura falsa del 77 %; la real es ~9 %.
- `IdArticulo` debe ir codificado en la URL (`*`, `,,`, `GEN. PARTE`).
- `Profundo` lo leen etiquetas de fabricación, cálculo de cajas y palets: rellenarlo cambia lo impreso.
- La carpeta del proyecto Android lleva tilde; se sortea con `android.overridePathCheck=true`.
- El README dice que la API «encola» fotos y que falta servidor: está desfasado; publica y está desplegada.

## 8. Riesgos y pendientes

- Sin autenticación ni TLS: cualquiera en la LAN puede escribir en el ERP a través de la API. El historial
  permite deshacer medidas, pero las fotos y los EAN vinculados no se deshacen desde la API.
- Discrepancias catálogo/ERP sin resolver (p. ej. medidas «sin patas»): decidir qué fuente manda.
- Coordinarse con quien ya sube fotos a mano desde AHORA.
- Copia de seguridad del volumen `estado` (historial).

## 9. Checklist de relevo

- [ ] Acceso al servidor y al `.env`; credencial de escritura `detalles_escritura` en el gestor de contraseñas.
- [ ] Montaje CIFS de `/mnt/goyco-imagenes` persistente (fstab) y `/salud` en `ok`.
- [ ] APK compilado y guardado; PDA con el perfil de escaneo configurado.
- [ ] Probado deshacer un cambio desde `/docs`.
