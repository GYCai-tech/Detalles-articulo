# Detalles de artículos

Completar **peso, dimensiones y fotos** del maestro de artículos del ERP AHORA
(`GOMEZYCRESPO`, SQL Server en `SRVAHORA`), midiendo con el móvil en el almacén.

```
Android (Kotlin)  ──HTTPS──>  API FastAPI  ──>  ERP SQL Server
   escanea EAN                   valida            dbo.ARTICULOS
   mide y pesa                   registra
   fotografía                    deshace
```

**La app no habla con SQL Server.** Las credenciales dentro de un APK son
credenciales públicas: un APK se descompila en minutos, y ese login no da acceso
solo a `ARTICULOS`. Además `10.0.0.6:1433` no es alcanzable fuera de la LAN. La
API es el único componente con credencial de escritura.

| Carpeta | Qué es |
|---|---|
| `api/` | FastAPI. Único punto con acceso de escritura. |
| `medidas/` | Reglas de negocio verificadas + scripts CLI (CSV masivo). |
| `android/` | App Kotlin + Compose. Compila; sin probar en dispositivo. |

`medidas/reglas.py` es la fuente única: lo usan igual la API y los scripts.

## Estado actual del maestro

25.510 artículos en `dbo.ARTICULOS`. Relleno de los campos:

| Campo | Relleno |
|---|---|
| Peso | 33,6 % |
| Ancho | 17,2 % |
| Alto | 15,6 % |
| Largo | 11,4 % |
| Profundo | 9,2 % |
| Foto | **9,0 %** (2.303 artículos) — ver abajo |

> **Trampa:** AHORA guarda "sin dato" como `0`, no como `NULL`. Cualquier query
> que mida cobertura tiene que filtrar `<> 0`, no `IS NOT NULL`.

## Por qué se escribe directo en la tabla

AHORA trae tres mecanismos de importación (staging `Ahora_Importacion_Articulos`,
el framework `Importacion_Excel`, y el robot de fotos `PersPRobotImportaArticulos`).
Para peso y dimensiones no hacen falta, y esto está **verificado, no supuesto**:

Se auditó el trigger `dbo.Articulos_UTrig` (1.093 líneas, se dispara en todo
`UPDATE`). Todas sus escrituras están dentro de una guarda `IF UPDATE(columna)`
o de un `WHERE` que compara `Inserted` contra `Deleted` sobre una columna
concreta: `NoInventariable`, `IdPresentacion`, `Lotes`, `NSeries`, `IdDefProd`,
`IdEmbalaje`, `IdFamilia`, `Ecotasa`, `CosteEstandar`, `IdCaracteristicasGrupo`,
`IdArticulo`, `IdProveedorPreferencial`.

`Peso`, `Alto`, `Ancho` y `Largo` no aparecen en ninguna. Un `UPDATE` que solo
las toque ejecuta el trigger pero no dispara ni una escritura en cascada.

En la tabla hay además **otros dos triggers de UPDATE** (auditados el
23/07/2026, ninguno reacciona a las columnas de medida):

- `PersArticulos_UTrig` — personalización de 2024. Solo actúa si **cambia
  `CodBarras`**: si el EAN nuevo ya lo tiene otro artículo, hace `ROLLBACK`.
  Inerte para un UPDATE de medidas.
- `Articulos_Escandallo_Prod_IUTrig` — guardado por
  `IF UPDATE(IncluirEscandallo)`. Inerte si esa columna no va en el SET.

También verificado: las columnas de medida no tienen columnas calculadas ni
CHECK constraints que dependan de ellas, y ninguna de las dos tablas está en
replicación, CDC ni es temporal.

### Lo que sí hay que respetar

1. **`CantidadBulto` e `IdEmbalaje` no se tocan.** El trigger recalcula
   `CantidadBulto` desde `funDisposicion_Paletizado_getCantidadBulto` cuando
   cambia `IdEmbalaje`. Escribirla a mano descuadra el artículo.

2. **Tres artículos están blindados.** `Articulos_UTrig` hace `ROLLBACK` de
   cualquier `UPDATE` que toque `GEN. PARTE`, `PARTE_PRIMASALIDA` o
   `GEN. TRABAJO EXTERNO`. Los tres existen. Y como el trigger es *por
   statement*, incluir uno solo revierte el lote entero — de ahí que se escriba
   en lotes de 200 y con exclusión explícita en el `WHERE`.

3. **`Usuario` y `FechaInsertUpdate`** son `NOT NULL` y la única traza de quién
   tocó qué. Se rellenan siempre.

4. **Las fotos van aparte**, pero son más simples de lo que parecía. Ver la
   sección siguiente.

Queda sin verificar la capa de aplicación: nadie ha comprobado qué valida el
cliente de AHORA al abrir la ficha del artículo.

## Cómo funcionan las fotos

Auditado, no supuesto.

**La imagen no está en la base de datos.** `dbo.Objetos_Imagenes` guarda solo
metadatos; el fichero vive en disco. La ruta base no está en el código: sale de
`Ceesi_configuracion` donde `PARAMETRO = 'PATHIMAGENES'` (hoy
`\\10.0.0.2\goyco\AHORA\Documentos\IMAGENES`). **Léela de ahí**, no la escribas
a mano: es configuración y puede cambiar.

La tabla es polimórfica — `Objeto` vale `Articulo` (19.852), `Articulo_Maquina`,
`Articulos_Familia`, `Operacion` — y enlaza por `IdDocObjeto = ARTICULOS.IdDoc`,
**no** por `IdArticulo`.

Reglas verificadas:

- **No hay trigger de INSERT ni de UPDATE.** El único trigger de la tabla es
  `Objetos_Imagenes_DTrig`, y es `FOR DELETE`: impide borrar la imagen principal
  salvo que sea la única. Insertar una foto no dispara nada.
- **PK compuesta:** `(Objeto, IdDocObjeto, NombreFichero)`. El mismo nombre de
  fichero dos veces para el mismo artículo viola la clave.
- **`IdDoc` es identity** — no se envía. `Usuario`, `FechaInsertUpdate`,
  `Principal`, `Orden` e `IdClasificacion` tienen valor por defecto.
- **Varias fotos por artículo están soportadas** (una llega a 12), siempre con
  exactamente una `Principal`. `Principal` por defecto es `0`, que es justo lo
  que interesa: una foto nueva no destrona a la que ya había.

Es decir, el insert mínimo es:

```sql
INSERT INTO dbo.Objetos_Imagenes
       (Objeto, NombreFichero, IdDocObjeto, Descrip, Usuario)
VALUES ('Articulo', :fichero, :iddoc, :descrip, :usuario)
```

**Primero el fichero, después la fila.** Si falla el insert queda un fichero
suelto que nadie ve. Al revés queda un registro que apunta a la nada — que es
exactamente la enfermedad que tiene esta base de datos:

### El 88 % de los registros de foto están rotos

De 19.852 registros, **17.513 apuntan a un fichero que no existe**. Solo 2.339
tienen imagen real, para 2.303 artículos distintos.

| Año | Con fichero | Huérfanos |
|---|---|---|
| 2022 | 1.350 | **17.437** |
| 2023 | 440 | 6 |
| 2024 | 231 | 1 |
| 2025 | 115 | 0 |
| 2026 | 203 | 69 |

Casi todo viene de una carga masiva de 2022 hecha por el usuario `dbo`, que creó
una fila `<IdArticulo>.jpg` por artículo existiera o no el fichero. Lo que la
gente ha subido a mano desde entonces está bien.

Los 69 de 2026 son otra cosa y son evitables: el nombre de fichero apunta a
**otro artículo**.

```
40000008 -> 25601124.jpg      51000157 -> 50502020.jpg
40000011 -> 25601124.jpg      51000158 -> 50502020.jpg
40000020 -> 25601124.jpg      51000161 -> 50502020.jpg
```

Es reutilización de fotos: se referencia la imagen de otro artículo. Cuando la
imagen de origen era ya uno de los huérfanos de 2022, la rotura se propaga. El
ERP no comprueba que el fichero exista al guardar la referencia.

**Consecuencia práctica:** cualquier consulta que cuente fotas por
`persVFotoArticulo` da 77 %, y es mentira. La cobertura real es 9 %.

### La carpeta no es solo de artículos, y no la alimenta nadie automático

Comprobado por los tiempos de los 2.662 ficheros: se escriben entre las 07:00 y
las 15:00, con pico a mediodía. Es horario de oficina, no un proceso programado.
Y el fichero y su fila aparecen con 20-175 segundos de diferencia — es el
cliente de AHORA haciendo las dos cosas en una sola acción del usuario.

Eso explica también por qué hay **tres convenciones de nombre**: AHORA conserva
el nombre que el fichero tenía en el PC de quien lo sube. `gilberto` nombra por
código (`51000168.jpg`), `ricardo` por descripción (`CONTERA CON ROSCA Ø8...`),
y los 31 GUID venían ya así. No hay regla que respetar.

La carpeta la comparten **todos** los tipos de imagen del ERP: `Articulo`,
`Articulo_Maquina`, `Articulos_Familia` y `Operacion`.

Además hay 262 ficheros (364 MB, el 23 %) que no tienen fila en
`Objetos_Imagenes`:

| Qué | Ficheros | Tamaño |
|---|---|---|
| `INC_*_<fecha>_<hora>.png` — del gestor de incidencias | 44 | **165 MB** |
| Nombrados como código de artículo | 94 | 109 MB |
| Otros | 123 | 85 MB |
| `Thumbs.db` | 1 | 5 MB |

Los `INC_*` llevan cayendo ahí desde enero de 2024 a 3,75 MB por fichero. Es la
razón de que la app **reduzca las fotos antes de subirlas** (`datos/Imagenes.kt`):
a 1600 px y calidad 85 quedan en 200-400 KB. Sin eso, fotografiar los ~600
artículos del catálogo metería 2-4 GB de golpe — más que todo lo acumulado en
ese recurso desde 2005.

### Quién está haciendo esto ya

`ricardo` y `gilberto` llevan 518 fotos cada uno y subieron ayer y anteayer. El
proceso actual es manual desde el cliente de AHORA. Antes de montar un camino
paralelo desde el móvil conviene hablar con ellos: puede que la app les ahorre
trabajo, o puede que se pisen.

## La API

```bash
python -m venv .venv && .venv\Scripts\activate
pip install -r requirements.txt
uvicorn api.main:app --host 0.0.0.0 --port 8000
```

**La app no tiene login.** Es una herramienta interna solo alcanzable en LAN y
en el almacén se usa un único usuario: la API firma todo lo que escribe en el
ERP con `API_USUARIO` (por defecto `ricardo`), tanto en `ARTICULOS.Usuario`
como en `Objetos_Imagenes.Usuario`. La contrapartida asumida: cualquiera en la
red interna puede escribir a través de la API; el historial hace reversible
cualquier cambio de medidas.

Documentación interactiva en `/docs`.

| Endpoint | Para qué |
|---|---|
| `GET /articulos/buscar?codbarras=` | Escáner. **Devuelve lista**, no un objeto. |
| `GET /articulos/buscar?texto=` | Respaldo cuando la etiqueta no se lee. |
| `GET /articulos/pendientes` | Cola de trabajo. |
| `GET /articulos/{id}` | Ficha con medidas actuales. |
| `PUT /articulos/{id}/medidas` | Escribe. Registra el valor anterior. |
| `POST /articulos/{id}/foto` | **Encola**, no publica en el ERP. |
| `GET /historial` · `POST /historial/{id}/deshacer` | Auditoría y deshacer. |

Detalles que la app debe respetar:

- **`IdArticulo` hay que codificarlo en la URL.** Existen artículos llamados
  `*`, `,,` y `GEN. PARTE`. Sin `urlEncode` la petición ni sale del móvil.
- **La búsqueda por código devuelve una lista.** 62 artículos comparten
  `CodBarras = '0'`. La app tiene que desambiguar, no coger el primero.
- **Un `503` con `causa: "sin_credenciales"` no es un fallo de red.** Significa
  que la API no tiene permiso de escritura y la medida **no se ha guardado**.
  Ya no es el estado normal (la credencial existe), pero la app lo distingue
  por si el `.env` de un despliegue nuevo llega incompleto.

Cada campo escrito guarda su valor anterior en `historial`. Es lo único que hace
reversible un error de tecleo en el almacén; un `UPDATE` sin eso es definitivo.

## La app Android

```bash
cd android
gradlew.bat :app:assembleDebug          # -> app/build/outputs/apk/debug/
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Kotlin + Compose, `minSdk 26`, sin dependencias de pago. La foto la saca la
cámara del sistema vía `FileProvider`.

### Escáner: el físico de la PDA primero, la cámara de respaldo

El dispositivo del almacén es una **PDA Honeywell con escáner integrado**. La
app lo usa **sin SDK**: escucha el broadcast que emite el servicio de escaneo
de la PDA, y el gatillo funciona desde cualquier pantalla (escanear el
siguiente artículo no obliga a volver atrás). Hay que configurarlo una vez en
la PDA:

> Ajustes → Honeywell Settings → Scanning → Internal Scanner → perfil Default
> → **Data Processing Settings**:
>
> - **Data Intent**: activado
> - **Intent Action**: `es.gyc.detalles.SCAN`
> - **Keyboard wedge / Scan to intent por teclado**: desactivado (si no, el
>   código se escribiría además como texto en cualquier campo con foco)

El código llega en el extra `data` del intent. Si la acción no coincide
letra a letra, el escaneo no llega a la app y no da ningún error: revisar ahí
primero.

El botón **Cámara** queda como respaldo para móviles normales: usa el módulo
de Google Play (`play-services-code-scanner`), sin permiso de cámara propio ni
CameraX.

### Ajustes

Pantalla dedicada, accesible desde la lupa. Un solo campo:

- **Servidor** — dirección de la API. Acepta `10.0.0.12`, `10.0.0.12:8000` o la
  URL entera; si falta el puerto se asume `8000`.

La app **no tiene login**: arranca directamente en la búsqueda. Todo lo que se
mide o se fotografía queda en el ERP a nombre del usuario único del almacén,
que configura la API (`API_USUARIO`), no el móvil.

### Detalles que resuelve la app

- Codifica `IdArticulo` en la URL con `%20` (no `+`): existen artículos `*`,
  `,,` y `GEN. PARTE`.
- Si un código de barras devuelve varios artículos, los lista y avisa en vez de
  coger el primero.
- Distingue el `503 sin_credenciales` de un fallo de red y dice **"NO se ha
  guardado"** en lugar de reintentar en bucle.
- Tras guardar, relee la ficha del ERP en vez de asumir que se escribió lo
  enviado.
- Redondea a 3 decimales al mostrar: el ERP guarda `decimal(38,14)` y un peso
  sale como `10.48200035095215`.

### Limitaciones conocidas

- **Sin cola offline.** Si se va la WiFi, la medida se pierde y hay que
  repetirla. Es el siguiente candidato a mejora.
- **HTTP en claro y sin autenticación.** Vale mientras la API no salga de la
  LAN. Si algún día se usa desde 4G hay que poner TLS y recuperar un login, no
  añadir el dominio a `seguridad_red.xml`.
- **Compila, pero no se ha ejecutado en un móvil.** Nadie ha visto todavía la
  app funcionando.
- La carpeta del proyecto lleva tilde y AGP lo rechaza; está sorteado con
  `android.overridePathCheck=true` en `gradle.properties`. Funciona porque no
  hay código nativo. Si se añade NDK, habrá que mover el proyecto.

## Uso de los scripts (carga masiva desde CSV)

**1. Exportar lo que falta:**

```bash
python -m medidas.exportar_pendientes --solo-incompletos
```

Deja un CSV en `salidas/`. Se abre en Excel de doble clic (`;` y UTF-8 BOM).

**2. Rellenar** las columnas `Peso`, `Alto`, `Ancho`, `Largo` en ese fichero.
Unidades del ERP: **peso en kg, dimensiones en cm**. Dejar a `0` o vacío lo que
no se sepa — no se pisa lo que ya hubiera.

**3. Ver el diff** (no escribe nada):

```bash
python -m medidas.actualizar_medidas salidas/medidas_20260721.csv
```

**4. Aplicar:**

```bash
python -m medidas.actualizar_medidas salidas/medidas_20260721.csv --aplicar
```

## Credenciales

Dos usuarios distintos a propósito, para que un script de exploración no pueda
escribir por accidente:

- `SQLSERVER_USER` — lectura. `gestor_incidencias` es `db_datareader`.
- `SQLSERVER_WRITE_USER` — escritura. `detalles_escritura`, verificado el
  23/07/2026 con UPDATE e INSERT reales dentro de una transacción revertida.
  Tiene más permisos de los pedidos (también `SELECT` y `DELETE` sobre las dos
  tablas); no estorba, pero se pidió el mínimo.

## Calidad de los datos que ya hay

Hay basura en producción, conviene no ampliarla:

- Un artículo con `Peso = -113,4` kg.
- Un artículo con `Alto = 261.010` cm (2,6 km).

`medidas/reglas.py` define cotas de cordura y rechaza los valores fuera de rango
antes de escribir.

Además, la cola de pendientes sin filtrar arranca con artículos de sistema
(`*` = "NUMERO DE BULTOS = 10", `,,` con descripción `0`). No es una lista de
trabajo utilizable tal cual.

### `Largo` y `Profundo` no son lo mismo

**Este es el error más fácil de cometer, y estaba a punto de cometerlo yo.**

El "largo" de las fichas de catálogo se guarda en la columna **`Profundo`**, no
en `Largo`. Verificado cruzando los 370 artículos del catálogo que tienen
medidas parseables con la tabla:

| Campo del catálogo | `Largo` | `Ancho` | `Alto` | `Profundo` |
|---|---|---|---|---|
| largo | 27 | 43 | 38 | **147** |
| ancho | 19 | **150** | 20 | 30 |
| alto | 23 | 15 | **144** | 27 |

Con los tres valores a la vez, `largo→Profundo, ancho→Ancho, alto→Alto` encaja
en 88 artículos; el siguiente mejor mapeo, en 16.

Los dos campos significan cosas distintas y conviven:

- **`Profundo`** — el fondo de un objeto 3D, o el lado largo de una chapa.
- **`Largo`** — la longitud de un producto lineal: tubos, arcos, perfiles,
  barras, tirantes.

```
SOLO Largo:     TUBO 30x30 INICIAL 2,16m        L=2165  A=30   H=30
                ARCO TUBO DE 10 M               L=600   A=4    H=8

SOLO Profundo:  CHAPA GALVA. FORMATO 3000x1500x1,5   P=3000  A=1500  H=1,5
```

Por eso el formulario de la app ordena los campos `Alto · Ancho · Fondo ·
Largo`, etiqueta `Profundo` como «Fondo / largo del producto», y avisa bajo
`Largo` de que es solo para tubos y barras.

### Las unidades no son homogéneas

En las mismas columnas conviven dos sistemas:

| Tipo de artículo | Unidad | Ejemplo |
|---|---|---|
| Producto de catálogo | **cm** | `JAULA ECONOMICA-3` → 104 × 63 × 150, y la ficha dice `104*63*150 cm` |
| Materia prima y fabricación | **mm** | `CHAPA GALVA. FORMATO DE 3000x1500x1,5` → 3000 × 1500 × 1,5 |

`TUBO 30x30 INICIAL 2,16m` guarda `Largo = 2165`: en cm serían 21,65 metros; en
mm, 2,165 m, que es lo que dice su descripción.

Esto significa que **la media de una columna no dice nada** y que ningún límite
de cordura puede detectar un error de unidad. Este proyecto apunta al catálogo
comercial, que va en cm, y por eso la app rotula «cm» — pero conviene saber que
la columna no es homogénea si algún día se explota para logística o aduanas.

### El catálogo y el ERP siguen sin coincidir en algunos casos

Ya explicado el mapeo, quedan discrepancias reales. Para `10101001`:

| Fuente | Medidas |
|---|---|
| ERP | Alto 100 · Ancho 63 · Profundo 54 · Peso 10,482 kg |
| Catálogo | `sin patas: 64*50*38 cm` · `Paquete: 5,045 kg` |

El catálogo mide **sin patas** y el ERP el producto montado; y el peso del ERP
casi dobla el del catálogo. De 578 artículos comparados, el peso del ERP se
parece al «peso producto» del catálogo en 188 casos y no se parece a ninguna de
las dos columnas del Excel en 208.

No está resuelto. Conviene decidir qué fuente manda antes de mandar a nadie a
medir, o habrá una tercera versión de las medidas.

## Estado

- [x] Reglas verificadas contra los TRES triggers de UPDATE del ERP
- [x] API funcionando y probada contra el ERP real (14 casos, solo lectura)
- [x] **Credencial de escritura** — `detalles_escritura`, verificada con
      escrituras reales revertidas en las dos tablas. De paso se descubrió y
      corrigió que los triggers falsean el `rowcount` del driver
      (`erp.actualizar` usa ahora `SET NOCOUNT ON` + `@@ROWCOUNT`).
- [ ] **Servidor donde alojar la API** — sin decidir.
- [x] Mecanismo de fotos auditado (sin trigger de INSERT; el camino es viable)
- [x] Fotos: la API las publica de verdad (fichero primero, fila después)
- [x] App Android: compila y genera APK
- [ ] Probar la app en un móvil real
- [ ] Hablar con `ricardo` y `gilberto`, que ya suben fotos a mano
- [ ] Decidir qué hacer con los 17.513 registros de foto huérfanos
- [ ] Resolver la discrepancia catálogo/ERP
