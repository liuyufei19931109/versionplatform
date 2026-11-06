# Plataforma de despliegue

**Una herramienta que puedes alojar en tu propia infraestructura para compilar aplicaciones, desplegar servicios y comprobar qué se ha publicado en cada versión.**

[简体中文](README.md) · [English](README.en.md) · Español　|　[Inicio rápido (中文)](docs/quickstart.md) · [Conectar un servicio (中文)](docs/first-service.md) · [Manual de uso (中文)](docs/user-guide.md) · [Comprobaciones del proyecto](.github/workflows/checks.yml)

Antes del despliegue puedes revisar el commit, el artefacto y los nodos de destino. Después, el mismo registro indica quién inició la operación, qué nodos superaron las comprobaciones de estado y en qué paso se detuvo una tarea fallida.

- **Sigue una compilación.** Elige una rama y un commit. Consulta los pasos de ejecución, los registros y el artefacto generado.
- **Pasa de preproducción a producción.** Revisa el historial de cada nodo antes de confirmar. Para desplegar en producción se requiere la validación en preproducción y el código de un solo uso de quien inicia la operación. Puedes volver a desplegar un artefacto anterior desde el historial.
- **Revisa una versión completa.** Agrupa módulos de frontend, backend y Flutter Android en un mismo plan. Los servicios aportan sus resultados de despliegue y de salud. Para los paquetes Android, el distribuidor confirma la entrega. El resumen distingue lo publicado, lo aplazado y lo pendiente de confirmar.

**Versión actual** `0.1.0-preview.1` · Versión preliminar distribuida como código fuente. La interfaz está disponible en español, inglés y chino simplificado. Para empezar, utiliza un entorno de pruebas aislado.

## Inicio rápido

Instala JDK 21, Maven 3.9 y Node.js 22 o 24. Descarga o clona el repositorio y abre dos terminales en su directorio raíz. La prueba local usa H2 y puede iniciarse sin acceso a tus servidores de aplicaciones.

En la primera terminal, inicia el backend. El ejemplo para Bash solicita la contraseña inicial del administrador sin mostrarla en pantalla. Usa al menos 12 caracteres y como máximo 72 bytes en UTF-8.

```bash
repo="$(pwd)"
mvn -f backend/pom.xml -DskipTests package && (
  set -e
  umask 077
  instance="$HOME/.local/share/release-platform-preview"
  mkdir -p "$instance"
  cd "$instance"
  read -r -s -p 'Contraseña inicial de admin: ' RELEASE_BOOTSTRAP_ADMIN_PASSWORD
  printf '\n'
  export RELEASE_BOOTSTRAP_ADMIN_PASSWORD
  java -jar "$repo/backend/target/release-platform-0.1.0-preview.1.jar" \
    --server.address=127.0.0.1 --server.port=8080 --release.workspace=./workspaces
)
```

En la segunda terminal, inicia el frontend.

```bash
cd frontend
npm ci
VITE_API_PROXY=http://127.0.0.1:8080 npm run dev -- --host 127.0.0.1 --strictPort
```

Abre `http://127.0.0.1:5173`, inicia sesión como `admin` con la contraseña que elegiste y vincula una aplicación de autenticación. En Windows, sigue las [instrucciones de PowerShell (中文)](docs/quickstart.md). Puedes elegir el idioma de la interfaz antes de iniciar sesión.

## Vista del panel

![Panel con servicios de ejemplo, historial de despliegues y cola de ejecución](docs/assets/workspace-demo.png)

*Todos los servicios, usuarios y registros de la imagen son ficticios.*

La compilación genérica con Dockerfile, el registro de imágenes procedentes de sistemas de CI externos y las comprobaciones iniciales de conexión figuran en la [hoja de ruta (中文)](docs/roadmap.md). Consulta la [guía de contribución](CONTRIBUTING.md) y la [política de seguridad](SECURITY.md) antes de enviar un informe.
