# API de detalles de articulos. Solo la API: la app Android se compila aparte.
#
# La imagen necesita el driver ODBC 18 de Microsoft (pyodbc solo no basta),
# que es el mismo paso manual que documenta despliegue/README.md para la
# instalacion sin Docker. python:3.12-slim es Debian 12 (bookworm), que es la
# version para la que Microsoft publica el paquete.
FROM python:3.12-slim

RUN apt-get update \
 && apt-get install -y --no-install-recommends curl gnupg ca-certificates \
 && curl -fsSL https://packages.microsoft.com/keys/microsoft.asc \
      | gpg --dearmor -o /usr/share/keyrings/microsoft.gpg \
 && echo "deb [signed-by=/usr/share/keyrings/microsoft.gpg] https://packages.microsoft.com/debian/12/prod bookworm main" \
      > /etc/apt/sources.list.d/mssql-release.list \
 && apt-get update \
 && ACCEPT_EULA=Y apt-get install -y --no-install-recommends msodbcsql18 \
 && apt-get purge -y gnupg \
 && apt-get autoremove -y \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Solo lo que la API importa. El .env NO se copia: las credenciales llegan
# por variables de entorno (env_file del compose), nunca dentro de la imagen.
COPY api/ api/
COPY medidas/ medidas/

# El SQLite (historial + registro de fotos) vive en /datos, que el compose
# monta como volumen: sin el, cada actualizacion de la imagen borraria lo
# unico que permite deshacer un error de medida.
RUN useradd --system --uid 1000 detalles \
 && mkdir /datos \
 && chown detalles /datos
USER detalles
ENV API_BD_LOCAL=/datos/estado.sqlite

EXPOSE 8000

HEALTHCHECK --interval=60s --timeout=10s --start-period=15s \
  CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:8000/salud', timeout=5)"

CMD ["uvicorn", "api.main:app", "--host", "0.0.0.0", "--port", "8000"]
