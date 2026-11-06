# Operación y despliegue del hardening

## Base de datos

Producción usa Flyway y `ddl-auto=validate`, con `clean-disabled=true` y
`baseline-on-migrate=false`. Nunca activar baseline automático sobre una base no
revisada. La aplicación falla si el esquema o las migraciones no son compatibles.

Cadena en `src/main/resources/db/migration` y `src/main/java/db/migration`:

| Versión | Efecto |
|---|---|
| V1 | Esquema completo para una base nueva, generado desde entidades y revisado |
| V2 | Integra scripts históricos, FK de perfiles de rol, autor/fecha de cancelación, OTP/outbox e índices de integridad y consulta |
| V3 | Normaliza teléfonos con la misma libphonenumber que registro, incluidos prefijos 00; detecta equivalentes antes de escribir |
| V4 | Vigencia, identidad del desafío y descarte de correos OTP |
| V5 | Participación efectiva en cancelación y FK del outbox a notificación |
| V6 | Repara FK de perfiles de rol omitidas por coincidencia de nombres en otro esquema; comprueba la tabla mediante conrelid, conservando checksums previos |

Base nueva: crear una base PostgreSQL vacía y configurar DB_URL, DB_USERNAME,
POSTGRES_PASSWORD, JWT_SECRET. Arrancar la aplicación aplica V1..V6 y valida JPA.

Base existente:

1. Hacer backup y probar restauración en una copia aislada. Detener escritores.
2. Revisar usuarios duplicados por `lower(trim(correo_institucional))`, teléfonos
   equivalentes, acuerdos repetidos por solicitud y solicitudes PENDIENTE/ACEPTADA
   duplicadas por viaje/pasajero. Resolver con sus titulares; no fusionar ni borrar
   automáticamente. Revisar fechas académicas inválidas, contadores de pasajeros
   y capacidad con los responsables de datos.
3. Comparar el esquema con V1 (estructura anterior a campos nuevos). Con Flyway CLI,
   definir FLYWAY_URL, FLYWAY_USER y FLYWAY_PASSWORD **en el entorno**, luego ejecutar
   `flyway -baselineVersion=1 baseline` sobre la copia verificada. No usar una base
   vacía con baseline 1: se omitiría la creación inicial.
4. Arrancar el nuevo backend contra esa copia para aplicar V2..V6, revisar
   `flyway_schema_history`, ejecutar pruebas funcionales y repetir el procedimiento
   sobre el destino aprobado. Los conflictos detienen la migración transaccional;
   no se borran filas para forzarla. Los scripts de `db/migrations/` quedan como
   referencia histórica y **no deben ejecutarse además de Flyway**.

V2 toma nombres/apellidos del Perfil cuando existe, pues representa la edición
personal explícita. V3 conserva teléfonos históricos que no puede validar: no
inventa números. Los nuevos registros deben ser válidos según metadata E.164
(PE por defecto), lo cual no demuestra que la línea esté asignada ni su titularidad.
Autores de cancelación históricos permanecen NULL. No inferirlos de penalidades.

No editar versiones aplicadas. Para corregir, añadir otra versión. Un rollback de
aplicación exige evaluar compatibilidad del esquema; la restauración del backup es
una acción operativa explícita, no parte automática de este procedimiento.

Referencia: [inicialización de Spring Boot](https://docs.spring.io/spring-boot/how-to/data-initialization.html)
y [baseline Flyway](https://documentation.red-gate.com/flyway/reference/commands/baseline).

## Autenticación y correo

Configurable mediante propiedades Spring/variables equivalentes:

| Propiedad | Defecto |
|---|---|
| uniride.auth.otp-intentos | 5 |
| uniride.auth.otp-bloqueo-minutos | 15 |
| uniride.auth.otp-reenvio-segundos | 60 |
| uniride.auth.limite-cuenta | 30 solicitudes / ventana |
| uniride.auth.limite-ip | 100 solicitudes / ventana |
| uniride.auth.ventana-segundos | 900 |
| uniride.mail.reintento-ms | 30000 |
| uniride.viajes.vencimiento-ms | 60000 |
| uniride.ocr.timeout-segundos | 60 |

OTP se guarda con BCrypt; se aceptan desafíos antiguos en claro únicamente hasta
su expiración original de 15 minutos. Fallos y bloqueo persisten pese a HTTP 400.
Verificación/reenvío/entrega serializan el usuario. Límites de cuenta/IP y avisos
repetidos son locales, acotados en memoria: para múltiples réplicas se debe añadir
un presupuesto compartido en el proxy/Redis. Los contadores OTP y cooldown sí son
persistentes y funcionan entre réplicas. El servidor usa la IP remota efectiva;
habilitar forwarded headers solamente detrás de un proxy confiable.

El outbox se escribe junto con la notificación en la transacción de negocio. Un
scheduler **separado de viajes** procesa solo filas confirmadas, con bloqueo por
fila y `REQUIRES_NEW`. SMTP tiene timeouts de 5 s y reintento exponencial hasta una
hora entre intentos. Correos OTP vencidos, reemplazados o de cuentas verificadas se
descartan y se borra su cuerpo. Cuerpos entregados también se borran. Avisos de
autenticación repetidos se agrupan durante 15 minutos manteniendo REQUIRES_NEW.

El cuerpo en cola usa AES-GCM. Configurar `UNIRIDE_MAIL_OUTBOX_KEY` con una clave
Base64 aleatoria distinta de JWT. Por compatibilidad inicial se deriva de JWT_SECRET
si no se define. Mantener la misma clave hasta drenar pendientes; cambiarla sin
recifrar hace imposible recuperar sus cuerpos. No registrar claves/códigos ni
incluir `.env` en Git o imágenes. Sin EMAIL_ADDRESS no hay entrega SMTP: las filas
permanecen pendientes. Configurar EMAIL_PASSWORD del proveedor fuera del repositorio.

SMTP ofrece entrega **al menos una vez**: una caída después de aceptación del
proveedor y antes de marcar la fila puede causar duplicado. La unicidad por
notificación y los bloqueos evitan duplicación de tareas y envíos concurrentes;
garantía exactamente una vez exigiría un proveedor con clave idempotente.

Verificación real de correo pendiente de operador: configurar una cuenta y buzón
de prueba institucional, registrar, recibir/verificar, reenviar tras 60 s, simular
fallo de credenciales/red y restaurar; comprobar pendiente→enviado y descartar OTP
obsoleto. No se han usado credenciales reales para las pruebas automatizadas.

## OCR y ejecución

PDF/TXT admiten 1 MiB. TXT requiere UTF-8 y rechaza binario; importación admite
2000 líneas de hasta 2000 caracteres y 500 cursos. Las validaciones ocurren antes
de regex costosas. PDF exige firma real, 1..10 páginas, hasta 8 millones de píxeles
por página y 40 millones totales a 200 DPI, texto <=1 millón de caracteres y streams
de contenido expandidos <=2 MB. Se ejecuta en JVM hija con heap 256 MiB, dos procesos
simultáneos como máximo y timeout que destruye el proceso. Fallos se traducen a
mensajes seguros. Temporales se limpian al finalizar; un fallo del SO podría
requerir limpieza del directorio temporal por el operador.

La extracción no retiene una conexión de BD; la importación posterior es
transaccional. Tesseract conserva spa+eng y configuración de tessdata. Docker usa la imagen Java 21 Noble, con Tesseract 5 y sus datos, ejecuta como usuario sin privilegios y limita memoria/CPU/pids.
En Linux se invoca /usr/bin/tesseract por argumentos estructurados dentro del worker;
el timeout termina también los procesos descendientes. Tess4J se conserva como alternativa.
En Windows se requieren librerías nativas y tessdata configurados para PDF escaneado.
El límite de heap no limita por sí mismo toda la memoria nativa; usar también los
límites del contenedor. [Política PDFBox](https://pdfbox.apache.org/security.html).

Clock de negocio/JWT se inyecta en zona America/Lima. Callbacks JPA usan también
Lima explícita; no dependen de la zona del host. Backend escucha 0.0.0.0 y Compose
publica 8080 en el host: proteger exposición/TLS en el despliegue. PostgreSQL se
publica solo en loopback 5433. Para backend directo contra Compose, usar
`DB_URL=jdbc:postgresql://localhost:5433/uniride`.

## Pruebas

Java 21: `mvnw.cmd clean test`, `mvnw.cmd verify` (Windows) o `./mvnw` (Linux).
Suite rápida H2 conserva las 55 clases originales; las integraciones PostgreSQL
son adicionales y obligatorias en CI, sin omisión automática si el servicio falla.

Para PostgreSQL **temporal exclusivamente de pruebas**, crear una base vacía y
configurar UNIRIDE_TEST_PG_URL, UNIRIDE_TEST_PG_USER, UNIRIDE_TEST_PG_PASSWORD;
ejecutar `mvnw.cmd -Ppostgres-it test`. Nunca apuntar ese perfil a datos reales.
Valida migración nueva, baseline existente, reejecución, concurrencia y persistencia
OTP. GitHub Actions ejecuta ambas suites y falla si cualquiera falla.
