# Desplegar el primer servicio

[简体中文](first-service.md) · [English](first-service.en.md) · **Español**

Si aún no has iniciado la plataforma, sigue la [guía de inicio local](quickstart.es.md). Aquí desplegarás un pequeño servicio HTTP de Node en un nodo Linux dedicado a preproducción. Al terminar, podrás consultar el commit elegido, la versión de la imagen, el nodo de destino y el resultado de la comprobación de estado. Sustituye los dominios `.invalid` por los de tus propios recursos de prueba.

## 1. Preparar el equipo de compilación, los repositorios y el nodo de prueba

| Recurso | Qué necesitas |
| --- | --- |
| Equipo donde se ejecuta el backend de la plataforma | Con **Compilación local**, la cuenta que ejecuta el backend necesita un directorio de trabajo con permiso de escritura, Git, un cliente OpenSSH y acceso a un motor Docker que pueda descargar la imagen base. Si el backend se ejecuta en un contenedor, comprueba las herramientas, los permisos y el directorio de trabajo desde el propio contenedor. |
| Repositorio Git y registro de imágenes | Utiliza un repositorio Git de prueba y un espacio de nombres exclusivo para sus imágenes. La cuenta de compilación debe poder leer el repositorio Git y enviar imágenes al registro. El nodo de destino debe poder descargar esas imágenes. Conecta a la cuenta con permisos de Docker únicamente código de confianza. |
| Nodo de prueba dedicado | Instala un servidor SSH, Docker, Compose V2 y `curl`. La cuenta de despliegue necesita acceso al directorio del proyecto de prueba y permiso para ejecutar Docker. En este primer despliegue, vincula un solo nodo de preproducción. |

**Crea una rama remota `master`.** Al iniciar una compilación y en cada reintento, la plataforma comprueba que el commit elegido incluya el último commit de la rama remota `master`. Si creas un repositorio de prueba, añade los dos archivos siguientes directamente a `master`, crea el commit y publica esa rama en el remoto. Elegir `main` como **Rama predeterminada** solo cambia la preselección del formulario. La comprobación de `master` sigue siendo obligatoria.

Guarda `server.mjs` en la raíz del repositorio de prueba.

```js
import { createServer } from 'node:http'

createServer((req, res) => {
  if (req.url === '/healthz') {
    res.writeHead(200)
    res.end('ok')
  } else {
    res.writeHead(200)
    res.end('release demo v1')
  }
}).listen(8080, '0.0.0.0')
```

Añade un `Dockerfile` en ese mismo directorio.

```dockerfile
FROM node:22-alpine
WORKDIR /app
COPY server.mjs .
USER node
EXPOSE 8080
CMD ["node", "server.mjs"]
```

En el nodo de prueba, crea un directorio para el proyecto, por ejemplo `/srv/release-demo`. Guarda allí el archivo `compose.yaml` que aparece a continuación y sustituye la dirección de la imagen por la tuya. Durante el despliegue, la plataforma sustituye la referencia de imagen por la del artefacto elegido, descarga la imagen y vuelve a crear `demo-web`. El nodo comprueba el contenedor directamente, por lo que puedes dejar el puerto sin publicar.

```yaml
services:
  demo-web:
    image: registry.example.invalid/lab/demo-web:bootstrap
```

## 2. Configurar el registro de imágenes y SSH

1. Con una cuenta administradora, abre **Configuración general → Registro de imágenes**. Introduce el nombre del host del registro de prueba, por ejemplo `registry.example.invalid`, y las credenciales dedicadas. La ruta completa del repositorio de imágenes se configura más adelante, dentro del servicio.
2. En la configuración de OpenSSH de la cuenta que ejecuta el backend, establece `StrictHostKeyChecking yes` para el nodo de prueba. Verifica la huella digital de la clave pública del host con quien administra el nodo, a través de un canal de confianza. A continuación, añade la clave pública verificada al archivo `known_hosts` de esa cuenta con el nombre de host y el puerto utilizados para la conexión. Si usas un puerto distinto del predeterminado, escribe `[nombre-del-host]:puerto`. Si accedes al repositorio Git mediante SSH, configura aparte la confianza en el host de Git y utiliza una clave de solo lectura para el repositorio.
3. Abre **Servidores → Añadir servidor**. Selecciona **SSH directo** y **SSH Key**. Introduce la dirección del nodo de prueba, la cuenta de despliegue, el puerto y la ruta de la clave privada dedicada **en el equipo donde se ejecuta el backend**. Especifica `/srv/release-demo` como directorio del proyecto de Compose y `compose.yaml` como archivo. En este ejemplo se utiliza autenticación de clave pública. El modo de conexión con contraseña de la versión actual carece de verificación estricta de la clave del host.
4. Guarda los cambios y selecciona **Probar conexión**. Tanto la comprobación de SSH como la de Compose deben completarse correctamente, y `demo-web` debe aparecer en la lista de servicios. Esta prueba lee la configuración de Compose. El permiso para enviar imágenes se verifica durante la compilación y el de descargarlas, durante el despliegue.

## 3. Registrar el servicio y desplegarlo

| Dónde | Configuración del ejemplo |
| --- | --- |
| **Servicios → Nuevo servicio** | Elige un nombre y utiliza `demo-web` como identificador del servicio. Selecciona **Proyecto frontend** y **Compilación local**, introduce la URL del repositorio Git de prueba y establece `master` como **Rama predeterminada**. |
| **Ajustes de imagen**, en los detalles del servicio | En **Método de entrega del frontend**, elige **App Node (Dockerfile del repositorio)**. En **Ruta del Dockerfile**, introduce `Dockerfile`. En **Dirección completa del repositorio de imágenes**, utiliza `registry.example.invalid/lab/demo-web` con la dirección de tu registro. La raíz del repositorio es el contexto de compilación. La propia imagen proporciona el entorno de ejecución de Node para la aplicación. |
| **Entornos y clústeres**, en los detalles del servicio | Selecciona **Entorno de preproducción**, activa **Permitir despliegues** y marca solo el nodo de prueba que acabas de añadir. Elige Docker Compose como método de despliegue. Introduce `demo-web` en **Nombre del servicio Compose**, `8080` en **Puerto de comprobación de estado dentro del contenedor** y `/healthz` en **URL de comprobación de estado**. Utiliza el comando de inicio generado por la plataforma y guarda la configuración. |

Abre **Nueva compilación**, selecciona el servicio y la rama, y revisa y confirma el último commit. Elige **Solo compilar** y añade la tarea a la cola. Cuando termine correctamente, abre **Nuevo despliegue** y selecciona el artefacto generado, el entorno de preproducción y el nodo de prueba. Revisa los destinos y envía la tarea.

Abre los detalles de la tarea en **Historial de despliegues**. Comprueba que los tres resultados correspondan al mismo despliegue: el registro de compilación indica el commit que confirmaste, el registro del despliegue identifica a `demo-web` como destino y el nodo ha superado la comprobación de estado. En **Estado de los servicios**, ejecuta una comprobación para comparar el contenedor y la imagen en ejecución con el último despliegue registrado.

Si falla algún paso, consulta primero su registro. Para un error de rama, comprueba la rama remota `master` y el commit elegido. Si falla el envío de la imagen, revisa la ruta completa del repositorio y los permisos del registro. Si falla la comprobación de estado, verifica el puerto del contenedor, la ruta `/healthz` y que la aplicación escuche en `0.0.0.0`. Después de modificar el código, crea una **nueva compilación**. Reintentar la tarea original vuelve a usar el commit fijado en ella.

Continúa con la [guía de uso](user-guide.es.md).
