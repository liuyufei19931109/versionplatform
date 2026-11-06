<h1 align="center">Plataforma de despliegue</h1>

<p align="center"><strong>Una plataforma autoalojada para seguir cada versión desde el commit hasta producción</strong><br>
Fija el commit al compilar, revisa el artefacto y los destinos antes del despliegue y consulta el resultado de cada nodo.</p>

<p align="center">
  <a href="https://github.com/liuyufei19931109/versionplatform/actions/workflows/checks.yml"><img src="https://github.com/liuyufei19931109/versionplatform/actions/workflows/checks.yml/badge.svg?branch=main" alt="Estado de las comprobaciones"></a>
  &nbsp; <code>0.1.0-preview.1</code> · Versión preliminar · <a href="LICENSE">MIT</a>
</p>

<p align="center"><a href="README.md">简体中文</a> · <a href="README.en.md">English</a> · <strong>Español</strong></p>

<p align="center"><strong><a href="docs/quickstart.es.md">Inicio rápido</a></strong> &nbsp;·&nbsp; <a href="docs/first-service.es.md">Conectar el primer servicio</a> &nbsp;·&nbsp; <a href="docs/user-guide.es.md">Guía de uso</a></p>

![Panel con despliegues de ejemplo, entornos de destino y personas responsables](docs/assets/workspace-demo.es.png)

*Vista del panel · Los servicios, usuarios y registros de despliegue que aparecen aquí son ficticios.*

## Qué comprobar en cada despliegue

| Etapa | Qué puedes verificar |
| --- | --- |
| Compilación | Elige una rama y un commit, sigue los pasos y los registros, y localiza el artefacto generado por una compilación correcta. |
| Paso a producción | Revisa la validación en preproducción y el último despliegue de cada nodo de destino. Quien inicia el despliegue lo confirma con su propio código de un solo uso. |
| Revisión del resultado | Consulta las comprobaciones de estado por nodo y los pasos que han fallado. Para volver a una versión anterior, selecciona el artefacto correspondiente y vuelve a desplegarlo. |

Un plan de versión puede reunir módulos de frontend, backend y Flutter Android. En los servicios, el resultado se comprueba por nodo. En Android, el distribuidor confirma la entrega. El resumen indica qué se ha publicado, qué se ha aplazado y qué queda por confirmar.

## Prueba local

Instala **JDK 21, Maven 3.9 y Node.js 22 o 24**. La versión preliminar usa H2. Puedes iniciar sesión, vincular una aplicación de autenticación y explorar el panel antes de conectar un servidor de pruebas independiente.

1. Desde la raíz del repositorio, compila el backend y configura la contraseña inicial del administrador.
2. En una segunda terminal, instala las dependencias en `frontend/` e inicia el frontend.
3. Abre la dirección local e inicia sesión como `admin`. El selector de la esquina superior derecha permite elegir español, inglés o chino simplificado antes de iniciar sesión.

[Ver los comandos de inicio para tu sistema operativo →](docs/quickstart.es.md)

## Documentación y contacto

[Configuración de la instancia y logotipo (en chino)](docs/source-baseline.md) · [Hoja de ruta (en chino)](docs/roadmap.md) · [Contribuir (en chino)](CONTRIBUTING.md) · [Política de seguridad (en chino)](SECURITY.md)

**Responsable del proyecto**　[liuyufei19931109](https://github.com/liuyufei19931109) · [liuyufei@midatia.cn](mailto:liuyufei@midatia.cn)

Puedes escribir por correo para hablar del proyecto o proponer una colaboración. Para comunicar un fallo reproducible o sugerir una función, abre una incidencia en [GitHub Issues](https://github.com/liuyufei19931109/versionplatform/issues).
