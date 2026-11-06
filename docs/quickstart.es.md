# Inicio rápido en local

[简体中文](quickstart.md) · [English](quickstart.en.md) · **Español**

Esta guía corresponde a la versión `0.1.0-preview.1`. Al terminar, podrás acceder al panel de control con tu propia cuenta de administrador, cambiar el idioma y explorar las páginas de servicios, compilaciones y gestión de versiones. Una instancia nueva empieza sin servicios ni registros de despliegue.

Para probar la plataforma en local, ejecuta el backend Java y el frontend Vite con la base de datos H2 integrada. Necesitarás un equipo, dos terminales y un autenticador que genere códigos TOTP de seis dígitos. La primera instalación de dependencias requiere acceso a los repositorios de Maven y npm.

## 1. Preparar el código fuente y las herramientas

Descarga el código fuente del repositorio en formato ZIP y descomprímelo en una carpeta vacía, o ejecuta `git clone` con la URL del menú **Code** del repositorio. Busca el directorio que contiene tanto `backend/pom.xml` como `frontend/package.json`. Es la **raíz del repositorio**. Abre allí los dos terminales.

Instala **JDK 21, Maven 3.9.x y Node.js 22 o 24 con la versión de npm que incluye**. Comprueba las versiones:

```text
java -version
mvn -version
node --version
npm --version
```

La versión de Java que muestra `mvn -version` también debe ser la 21. Si Windows PowerShell bloquea `npm.ps1`, utiliza `npm.cmd --version` y los comandos `npm.cmd` de esta guía.

Para esta prueba, guarda los datos de la instancia en una carpeta independiente de tu perfil de usuario. La primera vez, elige un directorio nuevo y abre terminales locales nuevos para evitar que hereden ajustes `SPRING_*` o `RELEASE_*` de otros despliegues. Conserva el mismo directorio para los siguientes arranques de esta instancia.

## 2. Iniciar el backend

En el primer terminal, compila el backend y ejecuta el archivo JAR desde el directorio de la instancia. El nombre de usuario del administrador predeterminado es `admin`. En el primer arranque, establece una contraseña de **al menos 12 caracteres y un máximo de 72 bytes en UTF-8**. Los siguientes comandos ocultan la contraseña mientras la escribes.

### Windows PowerShell

Desde la raíz del repositorio, ejecuta:

```powershell
$repo = (Get-Location).Path
mvn -f backend/pom.xml -DskipTests package
if ($LASTEXITCODE -ne 0) { throw 'Falló la compilación del backend. Revisa la salida de Maven.' }

$jar = Join-Path $repo 'backend/target/release-platform-0.1.0-preview.1.jar'
$instance = Join-Path $env:LOCALAPPDATA 'release-platform-preview'
New-Item -ItemType Directory -Force -Path $instance | Out-Null
$initialPassword = Read-Host 'Establece la contraseña inicial de admin' -AsSecureString
Push-Location -LiteralPath $instance
try {
    $env:RELEASE_BOOTSTRAP_ADMIN_PASSWORD = [System.Net.NetworkCredential]::new('', $initialPassword).Password
    java -jar $jar --server.address=127.0.0.1 --server.port=8080 --release.workspace=./workspaces
} finally {
    Remove-Item Env:\RELEASE_BOOTSTRAP_ADMIN_PASSWORD -ErrorAction SilentlyContinue
    $initialPassword.Dispose()
    Pop-Location
}
```

### macOS / Linux (Bash)

Abre Bash en la raíz del repositorio. Si tu terminal de macOS utiliza zsh, escribe `bash` y pulsa Intro antes de continuar. Ejecuta:

```bash
repo="$(pwd)"
mvn -f backend/pom.xml -DskipTests package && (
  set -e
  umask 077
  instance="$HOME/.local/share/release-platform-preview"
  mkdir -p "$instance"
  cd "$instance"
  read -r -s -p 'Establece la contraseña inicial de admin: ' RELEASE_BOOTSTRAP_ADMIN_PASSWORD
  printf '\n'
  export RELEASE_BOOTSTRAP_ADMIN_PASSWORD
  java -jar "$repo/backend/target/release-platform-0.1.0-preview.1.jar" \
    --server.address=127.0.0.1 --server.port=8080 --release.workspace=./workspaces
)
```

Deja el primer terminal abierto. Abre la [comprobación de estado del backend](http://127.0.0.1:8080/api/health) en el navegador. La respuesta debe incluir `"status":"UP"` y el número de versión. Para iniciar sesión, utiliza la dirección del frontend del siguiente paso.

## 3. Iniciar el frontend

En el **segundo terminal**, ejecuta los comandos de tu sistema desde la raíz del repositorio. Vite reenvía al backend local las solicitudes de la página a `/api`.

**Windows PowerShell**

```powershell
cd frontend
npm.cmd ci
if ($LASTEXITCODE -ne 0) { throw 'No se pudieron instalar las dependencias del frontend. Revisa la salida de npm.' }
$env:VITE_API_PROXY = 'http://127.0.0.1:8080'
npm.cmd run dev -- --host 127.0.0.1 --port 5173 --strictPort
```

**macOS / Linux**

```bash
cd frontend
npm ci && VITE_API_PROXY=http://127.0.0.1:8080 \
  npm run dev -- --host 127.0.0.1 --port 5173 --strictPort
```

Abre el [panel de control local](http://127.0.0.1:5173). Si la [comprobación de estado a través del proxy del frontend](http://127.0.0.1:5173/api/health) también devuelve `UP`, el navegador puede acceder al backend a través de Vite. Ambos servicios solo escuchan en la dirección de bucle local.

## 4. Iniciar sesión y vincular el autenticador

1. En la página de acceso, introduce `admin` y la contraseña del paso 2. Al acceder por primera vez se abrirá **Seguridad de acceso**.
2. Selecciona **Iniciar vinculación**. Escanea el código QR con Google Authenticator u otra aplicación compatible con TOTP. También puedes introducir manualmente la clave que aparece en la página. El código QR tiene una validez de 10 minutos.
3. Introduce los seis dígitos que muestra el autenticador y selecciona **Verificar y habilitar**. Una vez verificado el código, se abrirá el panel de control. Para volver a iniciar sesión necesitarás tu nombre de usuario, la contraseña y un código nuevo.
4. Puedes elegir 简体中文, Español o English en la página de acceso o en la esquina superior derecha del panel de control. Abre **Servicios** o **Historial de despliegues** para ver las listas vacías de la nueva instancia.

Cada código de verificación se puede utilizar una sola vez. Si acabas de vincular el autenticador o verificar un código, espera a que aparezca el siguiente antes de volver a iniciar sesión. Comprueba que la hora del equipo donde se ejecuta el backend y la del móvil estén sincronizadas. El código QR y la clave de vinculación manual son credenciales de segundo factor. Guárdalos únicamente en tus propios dispositivos.

## 5. Detener la instancia, reiniciarla y hacer copias de seguridad

Pulsa `Ctrl+C` en el terminal del frontend. Después, pulsa `Ctrl+C` en el del backend y espera a que termine. La base de datos H2 en archivo guarda una copia de seguridad durante el cierre normal. Si fuerzas el cierre de Java o se interrumpe el suministro eléctrico, es posible que la copia no llegue a escribirse.

Con los comandos anteriores, las siguientes rutas son relativas al directorio de la instancia:

| Ruta | Contenido |
| --- | --- |
| `data/release-platform.mv.db` | Base de datos H2 con las cuentas de usuario, la configuración de los servicios y los registros de tareas |
| `data/credential-encryption.key`, `data/totp-encryption.key` | Claves locales necesarias para descifrar las credenciales y los secretos TOTP almacenados |
| `data/backups/shutdown-*/` | Directorio con la copia de H2 generada durante el cierre normal |
| `workspaces/` | Espacio de trabajo de las tareas que compilan el código fuente |

Comprueba que el directorio de la copia más reciente contiene `COMPLETE` y `database.zip`. Antes de marcarla como completa, la plataforma copia cada una de las dos claves de cifrado si existe. Restringe el acceso tanto a las copias como al directorio de la instancia, ya que contienen datos de las cuentas y claves. Si trasladas la instancia, copia por separado los artefactos guardados como archivos, por ejemplo, los paquetes de aplicaciones. El ZIP de H2 solo contiene la base de datos.

**Para reiniciar**, utiliza el mismo JAR, directorio de trabajo de la instancia y argumentos. Cuando ya existe la cuenta `admin`, puedes omitir `RELEASE_BOOTSTRAP_ADMIN_PASSWORD`. Volver a establecer esta variable no restablece la contraseña existente. Para el frontend, ejecuta en `frontend/` el comando de inicio del paso 3. Puedes omitir `npm ci` si no han cambiado el archivo de bloqueo ni las dependencias instaladas.

Para personalizar las rutas, configura `RELEASE_DATA_DIR` para los datos de H2 y `RELEASE_SHUTDOWN_BACKUP_DIR` para las copias generadas al cerrar. **Las rutas predeterminadas de las dos claves de cifrado son `./data/credential-encryption.key` y `./data/totp-encryption.key`, relativas al directorio de trabajo actual.** Cambiar solo `RELEASE_DATA_DIR` no traslada las claves. Al migrar una instancia existente, conserva las claves originales e indica sus archivos mediante `--release.credential-key-file=/ruta/absoluta` y `--release.totp-key-file=/ruta/absoluta`. También se puede configurar MySQL. La copia automática al cerrar descrita en esta guía solo se aplica a H2 en archivo.

## Solución de problemas de arranque

| Síntoma | Qué comprobar |
| --- | --- |
| La compilación indica que no se admite la versión 21 | Comprueba el directorio de Java que muestra `mvn -version`. Corrige `JAVA_HOME` y vuelve a abrir el terminal. |
| El backend se cierra inmediatamente y solicita la contraseña inicial del administrador | Establece una contraseña que cumpla los requisitos de longitud en el mismo terminal que inicia Java. Vuelve a arrancar desde el mismo directorio de la instancia. |
| El puerto 8080 o 5173 está ocupado | Si cambias `--server.port` en el backend, actualiza `VITE_API_PROXY` y reinicia Vite. Si solo cambias `--port` en el frontend, abre la página en el nuevo puerto. |
| La página se abre, pero falla la conexión al iniciar sesión o al llamar a la API | Comprueba primero el estado del backend y después el estado a través del proxy del frontend. Verifica que el backend esté listo y que el proxy apunte a la dirección y al puerto correctos. |
| Tras reiniciar parece que la instancia es nueva, o no se pueden descifrar las credenciales existentes | Comprueba el directorio de arranque, el de datos y las rutas de las claves. Vuelve al directorio original de la instancia y conserva la base de datos y las claves originales. |
| Quieres desplegar un servicio real | Prepara un repositorio de pruebas de confianza, las herramientas del método de compilación elegido y un registro de imágenes. Registra después tu propio nodo Linux de pruebas. Las compilaciones actuales y algunos scripts de ejecución dependen de Linux, Docker y SSH. Puedes abrir el panel local en Windows o macOS; para desplegar en Linux necesitas preparar esos recursos. |

Después de la prueba local, sigue la guía [Conectar tu primer servicio](first-service.es.md) para configurar tus propios recursos de prueba.
