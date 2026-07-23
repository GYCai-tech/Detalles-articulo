# Despliegue en 10.0.0.12

La API vive en `10.0.0.12`, que es **Linux** (puerto 22 abierto, sin RDP; ahí
corre también el Postgres `gyc_analytics` en el 5434).

Esa máquina es Linux y eso tiene una consecuencia que no es obvia:

> El ERP guarda la carpeta de imágenes como **ruta UNC de Windows**
> (`\\10.0.0.2\goyco\AHORA\Documentos\IMAGENES`, en `Ceesi_configuracion`).
> En Linux eso no se puede abrir. Hay que montar el recurso por CIFS.

Sin el montaje, las medidas funcionarían y **las fotos fallarían una a una**.
Por eso `api/config.py` se niega a arrancar en Linux si `API_MONTAJE_IMAGENES`
no está puesta: mejor no levantar que levantar a medias.

## Camino recomendado: Docker

Con Docker sobran los pasos 1, 2, 4 y 6 (usuario, driver ODBC, venv y
systemd): la imagen ya lleva el driver y el compose hace de supervisor. Lo que
**sigue siendo del host** es el montaje CIFS (paso 3) y el cortafuegos (7).

```bash
git clone https://github.com/GYCai-tech/Detalles-articulo.git
cd Detalles-articulo
cp .env.example .env && nano .env      # credenciales; host de SQL por IP
# ... montar CIFS en /mnt/goyco-imagenes con uid=1000 (paso 3) ...
docker compose up -d --build
curl -s http://localhost:8000/salud    # "estado": "ok"
```

Dos detalles que no son opcionales:

- **`SQLSERVER_HOST` por IP** (`10.0.0.6`). Dentro del contenedor el DNS
  corporativo no resuelve `SRVAHORA`; verificado que con IP conecta.
- **El montaje CIFS con `uid=1000`**, que es el usuario `detalles` dentro del
  contenedor. Con otro uid las fotos fallarán con permiso denegado.

El historial (SQLite) vive en el volumen `estado`; entra en el backup con
`docker run --rm -v detalles-articulo_estado:/d alpine cat /d/estado.sqlite`.

Los pasos siguientes documentan la instalación **sin** Docker, por si hiciera
falta.

## 1. Usuario y código

```bash
sudo useradd --system --home /opt/detalles-articulos --shell /usr/sbin/nologin detalles
sudo mkdir -p /opt/detalles-articulos
sudo chown detalles:detalles /opt/detalles-articulos
# copiar api/ y medidas/ y requirements.txt ahí
```

## 2. Driver ODBC de SQL Server

`pyodbc` no sirve de nada sin el driver. En Debian/Ubuntu:

```bash
curl https://packages.microsoft.com/keys/microsoft.asc | sudo tee /etc/apt/trusted.gpg.d/microsoft.asc
curl https://packages.microsoft.com/config/debian/12/prod.list | sudo tee /etc/apt/sources.list.d/mssql-release.list
sudo apt-get update
sudo ACCEPT_EULA=Y apt-get install -y msodbcsql18 unixodbc-dev
```

Comprobar: `odbcinst -q -d` debe listar `ODBC Driver 18 for SQL Server`.

## 3. Montar las imágenes por CIFS

La carpeta de fotos tiene **tres nombres para un mismo sitio**, y conviene no
confundirse al montarla:

| Nombre | Dónde se usa |
|---|---|
| `M:\AHORA\Documentos\IMAGENES` | Letra mapeada en los PCs del dominio |
| `\\gcdc001\goyco\AHORA\Documentos\IMAGENES` | Por nombre de host |
| `\\10.0.0.2\goyco\AHORA\Documentos\IMAGENES` | Por IP — es lo que guarda `Ceesi_configuracion` |

`gcdc001.GOYCO.local` resuelve a `10.0.0.2`; comprobado que las tres rutas dan
los mismos 2.661 ficheros. El recurso compartido es `goyco`; `AHORA/Documentos/
IMAGENES` es subcarpeta dentro de él.

`gcdc001` tiene pinta de ser el controlador de dominio de `GOYCO.LOCAL`, así que
el acceso va autenticado contra el dominio.

```bash
sudo apt-get install -y cifs-utils
sudo mkdir -p /mnt/goyco-imagenes
```

Credenciales en un fichero aparte, no en `/etc/fstab` (fstab es legible por
todo el mundo):

```bash
sudo tee /etc/samba/credenciales-goyco >/dev/null <<'EOF'
username=svc_detalles
password=...
domain=GOYCO.LOCAL
EOF
sudo chmod 600 /etc/samba/credenciales-goyco
```

**Usa una cuenta de servicio, no la de una persona.** Si se monta con la cuenta
de alguien, el día que cambie su contraseña o se vaya de la empresa, las fotos
dejan de subirse — y el síntoma no apuntará a la causa. Esa cuenta solo necesita
lectura y escritura sobre esa carpeta.

En `/etc/fstab`:

```
//10.0.0.2/goyco/AHORA/Documentos/IMAGENES  /mnt/goyco-imagenes  cifs  credentials=/etc/samba/credenciales-goyco,uid=detalles,gid=detalles,file_mode=0664,dir_mode=0775,vers=3.0,_netdev,nofail  0  0
```

Tres cosas de esa línea que son decisiones, no relleno:

- **Se monta la subcarpeta `IMAGENES`, no el recurso `goyco` entero.** El
  servicio solo necesita las fotos; no tiene por qué ver el resto del recurso
  compartido de la empresa.
- **Por IP y no por nombre.** `gcdc001` resuelve por el DNS del dominio, y esa
  máquina Linux puede no estar usándolo. La IP no depende de eso. Si prefieres
  el nombre, comprueba antes que `getent hosts gcdc001` responde.
- `uid=detalles` es lo que permite que el servicio escriba, y `nofail` evita
  que la máquina no arranque si el recurso no responde.

```bash
sudo mount -a
sudo -u detalles touch /mnt/goyco-imagenes/.prueba && sudo -u detalles rm /mnt/goyco-imagenes/.prueba
```

Si ese `touch` falla, para aquí: la API no va a poder subir fotos.

## 4. Entorno Python

```bash
sudo -u detalles python3 -m venv /opt/detalles-articulos/.venv
sudo -u detalles /opt/detalles-articulos/.venv/bin/pip install -r requirements.txt
```

## 5. `.env`

```ini
SQLSERVER_HOST=10.0.0.6
SQLSERVER_DB=GOMEZYCRESPO
SQLSERVER_PORT=1433

SQLSERVER_USER=gestor_incidencias
SQLSERVER_PASS=...

# El login con UPDATE sobre dbo.ARTICULOS e INSERT sobre dbo.Objetos_Imagenes.
SQLSERVER_WRITE_USER=
SQLSERVER_WRITE_PASS=

# La app no tiene login. Este nombre firma todo en ARTICULOS.Usuario y en
# Objetos_Imagenes.Usuario.
API_USUARIO=ricardo

# Imprescindible en Linux.
API_MONTAJE_IMAGENES=/mnt/goyco-imagenes
```

```bash
sudo chown detalles:detalles /opt/detalles-articulos/.env
sudo chmod 600 /opt/detalles-articulos/.env
```

## 6. Servicio

```bash
sudo cp despliegue/detalles-api.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now detalles-api
journalctl -u detalles-api -f
```

## 7. Cortafuegos

Solo desde la red interna. La API habla HTTP en claro, así que **no debe verse
desde fuera**:

```bash
sudo ufw allow from 10.0.0.0/24 to any port 8000 proto tcp
```

## 8. Comprobar

```bash
curl -s http://10.0.0.12:8000/salud | python3 -m json.tool
```

Tiene que responder `"estado": "ok"`. Si dice `degradado`, el propio JSON
señala qué falta:

| Campo | Qué significa |
|---|---|
| `lectura` | Conexión al ERP con el usuario de solo lectura |
| `escritura` | El login con permiso de `UPDATE`/`INSERT`. Sin él la app no guarda nada |
| `imagenes.escribible` | El montaje CIFS. Si es `false`, las fotos fallarán |
| `imagenes.segunErp` | Lo que el ERP tiene en `PATHIMAGENES`, para cotejarlo con el montaje |

Y en el móvil, en **Ajustes → Servidor**: `10.0.0.12:8000`. No hay más que
configurar: la app no tiene login.

## Pendiente

- **TLS y autenticación.** La API habla HTTP en claro y no tiene login: es
  asumible mientras solo se vea desde la LAN. Si algún día se usa desde fuera,
  hay que poner TLS y recuperar un login antes.
- **Copia del SQLite** (`api/estado.sqlite`). Contiene el historial de cambios,
  que es lo único que permite deshacer un error. Merece entrar en el backup.
