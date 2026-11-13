# Guía de uso y soporte — Detalles de artículos (PDA de almacén)

Esta guía es para quien **mide, pesa y fotografía artículos en el almacén** con la PDA, y para quien
atiende sus incidencias sin ser programador.

Para mantener o modificar el código, ver [DOCUMENTACION_TECNICA.md](DOCUMENTACION_TECNICA.md).

---

## 1. Qué es

Una aplicación para la **PDA del almacén** (o un móvil Android) que sirve para completar la ficha de los
artículos en el ERP **sin pasar por la oficina**:

- **peso** y **medidas**;
- **foto** del artículo;
- **código del fabricante**;
- **vincular el código de barras** de la etiqueta con el artículo.

> **Importante:** lo que se guarda aquí **se escribe en el ERP de verdad**, al momento. Las medidas se
> pueden deshacer (ver apartado 5), pero hay que mirar bien antes de pulsar *Guardar*.

## 2. Dónde está

| Qué | Dónde |
|---|---|
| App | «Detalles» en la PDA Honeywell del almacén (o un móvil Android con el APK instalado) |
| Servidor | `http://10.0.0.12:8000` (la app ya viene apuntando ahí) |
| Red | Solo funciona con la **WiFi de la empresa** |
| Código | GitHub `GYCai-tech/Detalles-articulo` (en el servidor: `/home/goyco/apps/detalles-artículo`) |

No pide usuario: todo queda en el ERP a nombre del usuario del almacén configurado en el servidor.

## 3. Cómo se usa

### 3.1 Encontrar el artículo

- **Escanear** la etiqueta con el gatillo de la PDA. Funciona desde cualquier pantalla.
- Sin escáner: botón **Cámara** (lee el código con la cámara del móvil).
- Sin etiqueta legible: escribir en **Código o descripción**.

Si el código lo comparten varios artículos, la app los lista y avisa: **elegir el correcto**, no el primero.
Si el código no está vinculado a ningún artículo, buscar el artículo por texto y vincularlo desde su ficha
(3.4).

En la lista de resultados, cada artículo enseña cuántas medidas le faltan y si tiene foto.

### 3.2 Medir y pesar

En la ficha del artículo:

| Campo | Qué poner |
|---|---|
| **Peso (kg)** | En kilos |
| **Alto (cm)** | Altura del producto **montado** |
| **Ancho (cm)** | Anchura |
| **Largo / fondo (cm)** | El largo del producto (lo que en las fichas de catálogo pone «largo») |
| **Código de fabricante** | La referencia del fabricante para este artículo |

Pulsar **Guardar**. Sale «Guardado.» y la ficha se vuelve a leer del ERP para confirmar lo que quedó.
Los campos cambiados se marcan como «Modificado» hasta guardar.

Valores imposibles (peso negativo, medidas gigantes) se rechazan con un aviso.

### 3.3 Hacer la foto

1. Botón **Foto**: se abre la cámara.
2. Revisar la foto y elegir:
   - **Subir** si el artículo no tenía foto (pasa a ser la principal);
   - si ya tenía: **Añadir** (queda junto a la actual) o **Reemplazar** (la nueva pasa a principal; la
     anterior no se borra);
   - **Descartar** para repetirla.

La app reduce la foto antes de enviarla, así que no importa la resolución del móvil.

### 3.4 Vincular un código de barras

Si se escaneó un código que no estaba en el ERP y luego se abrió el artículo por texto, la ficha ofrece
**Vincular a este artículo**. A partir de ahí, escanear esa etiqueta abrirá este artículo directamente.

### 3.5 Artículos protegidos

Si sale «Este artículo está protegido en el ERP…», es un artículo de sistema que no se puede modificar.
No hay nada que hacer: pasar al siguiente.

## 4. Preguntas habituales

| Pregunta | Respuesta |
|---|---|
| «Al escanear no pasa nada» | La PDA no está enviando el código a la app. Ver 6.1 |
| «“Ese código lo comparten N artículos”» | Hay artículos con el mismo código en el ERP. Elegir el correcto por la descripción |
| «Me he equivocado en una medida» | Corregirla y guardar otra vez. Si no se sabe el valor bueno, pedir a sistemas que la deshaga |
| «¿En cm o en mm?» | La app trabaja en **cm** (y kg). Algunas materias primas del ERP están en mm: no tocarlas si hay duda |
| «Se ha ido la WiFi y no se ha guardado» | La app no guarda en el móvil. Volver a la zona con WiFi y repetir |
| «¿Puedo borrar una foto?» | Desde la app no. Se hace desde el ERP (AHORA) |

## 5. Si no funciona

| Lo que se ve | Causa probable | Qué hacer |
|---|---|---|
| «No se llega al servidor. Comprueba que estás en la WiFi de la empresa.» | Sin WiFi o servidor parado | Comprobar la WiFi. Si sigue, avisar a sistemas (5.1) |
| «NO se ha guardado. La API todavía no tiene permiso de escritura…» | Falta o ha caducado la credencial de escritura en el servidor | **No repetir**. Avisar a sistemas |
| La foto falla con error de recurso de imágenes | La carpeta de fotos del ERP no está montada en el servidor | Avisar a sistemas (5.1) |
| «Error inesperado» | Fallo interno | Anotar el artículo y la hora y avisar |

### 5.1 Comprobaciones (sistemas)

Desde un navegador en la red: `http://10.0.0.12:8000/salud`. Tiene que decir `"estado":"ok"` y
`"lectura":"ok"`, `"escritura":"ok"`, `"escribible":true`. Lo que no esté en `ok` es lo que falla.

Por SSH en `10.0.0.12`:

```bash
cd /home/goyco/apps/detalles-artículo
docker ps --filter name=detalles-api      # ¿en marcha?
docker logs --tail 50 detalles-api        # errores
docker restart detalles-api               # reiniciar
mount | grep goyco-imagenes               # ¿está montada la carpeta de fotos?
```

Para **deshacer** una medida: abrir `http://10.0.0.12:8000/docs`, `GET /historial` para ver el cambio y
`POST /historial/{id}/deshacer` con su número.

## 6. Configuración de la PDA

### 6.1 Escáner Honeywell (una sola vez por PDA)

Ajustes → **Honeywell Settings** → **Scanning** → **Internal Scanner** → perfil **Default** →
**Data Processing Settings**:

- **Data Intent**: activado.
- **Intent Action**: `es.gyc.detalles.SCAN` (exactamente así; si una letra no coincide, no da error pero no
  llega nada).
- **Keyboard wedge**: desactivado (si no, el código se escribiría además en cualquier campo).

### 6.2 Instalar o actualizar la app

La instala sistemas desde el ordenador con el APK (`adb install -r app-debug.apk`).

## 7. Qué recoger antes de escalar

- Código del artículo, hora y qué se intentaba (medir, foto, vincular).
- Mensaje exacto (foto de la pantalla).
- Resultado de `http://10.0.0.12:8000/salud`.

## 8. Glosario

| Término | Significado |
|---|---|
| **PDA** | Terminal de mano del almacén con escáner integrado |
| **EAN / código de barras** | Número de la etiqueta del producto |
| **Vincular** | Decir al ERP que una etiqueta corresponde a un artículo |
| **Foto principal** | La que enseña el ERP en la ficha del artículo |
| **Largo / fondo** | Lado largo del producto (en el ERP, columna *Profundo*) |
