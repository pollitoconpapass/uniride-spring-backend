# Aclaraciones estructuradas del agente

`POST /api/agent/chat` puede incluir `aclaracion` en el `ChatResponse`. Voz usa el mismo contrato en `respuesta.aclaracion` dentro de `POST /api/agent/voz`. Angular puede elegir sus controles a partir de `campos`, independientemente de la redacción de `message`.

## Contrato v1

| Propiedad | Tipo | Uso |
|---|---|---|
| `version` | `1` | Versión del esquema de aclaración |
| `id` | UUID | Identifica el formulario activo en la conversación. Se obtiene de la respuesta y se devuelve dentro del mensaje de formulario |
| `operacion` | `string` | Nombre de la herramienta a la que corresponde la aclaración |
| `contexto` | `string` | Etiqueta de presentación del flujo |
| `campos` | `CampoAclaracion[]` | Campos faltantes, por corregir o para distinguir coincidencias |
| `alMenosUno` | `string[]` opcional | IDs de un grupo alternativo. Los campos del grupo tienen `obligatorio: false` y se completa al menos uno para resolver esa parte de la solicitud |
| `opcionesRapidas` | `boolean` opcional | Puede renderizarse como botones cuando hay un solo campo `select` |
| `respuestaBreve` | `valor` o `campo`, opcional | Orientación para la respuesta manual. `valor` admite un dato aislado y `campo` indica datos con etiqueta |

Cada campo tiene `id`, `etiqueta`, `tipo` y `obligatorio`. Los tipos son `text`, `textarea`, `number`, `date`, `time` y `select`. Los campos de texto pueden incluir `longitudMaxima` y los numéricos pueden incluir `minimo` y `maximo`. Un `select` contiene `opciones`, cada una con `valor` y `etiqueta`. `ayuda` se puede mostrar junto al control. `inicial` es opcional y representa un valor ya conocido. Cuando falta, inicia con el control vacío.

Java selecciona los campos a partir de requisitos de la operación, argumentos conocidos y validaciones de dominio. Puede resolver solicitudes reconocidas de forma local. Si Groq selecciona una herramienta, Java contrasta la acción, el rol y los argumentos con la petición actual y conserva el formulario que devuelva la herramienta cuando la aclaración sea válida. Generar el formulario no requiere otra consulta al proveedor. Las alternativas proceden de filtros permitidos y, cuando se ofrecen opciones de recursos, de resultados autorizados que se pueden distinguir. Java vuelve a comprobar la coincidencia con los valores recibidos. Si los datos visibles no permiten distinguir recursos, `message` solicita otro criterio.

`aclaracion` se omite en respuestas que no necesitan datos. Un formulario activo y una confirmación pendiente corresponden a etapas separadas. La propuesta se sigue leyendo desde `operacion.confirmacion`. `resultados` y `message` conservan su uso habitual.

## Envío desde Angular

1. Lee `respuesta.aclaracion` cuando `version === 1` y vincula la instancia del formulario al `conversationId` y a su `id`. Usa `contexto`, etiquetas y ayuda como texto.
2. Envía `POST /api/agent/chat` con el `conversationId` actual y el mensaje `Datos de aclaración <id>: <objeto JSON>`. Puedes enviar solo los controles completados. Los valores de fecha usan `YYYY-MM-DD` y los de hora `HH:mm`. Para un `select` envía `valor`.
3. Si quedan datos por completar, el servidor conserva los valores válidos y entrega un descriptor con los campos que necesita y un nuevo identificador. Reemplaza el formulario anterior, incluido su identificador.
4. Si la respuesta contiene `CONFIRMACION_REQUERIDA`, muestra el mecanismo de confirmación existente. Enviar el formulario solo aporta datos para preparar la propuesta.

El formulario se valida en la sesión autenticada. Un ID obsoleto devuelve HTTP 409 con `campos.codigo: "CLARIFICATION_EXPIRED"`. Claves no solicitadas, duplicadas o valores incompatibles devuelven HTTP 400. Una conversación ajena conserva el HTTP 404 y un cambio de rol invalida la conversación con HTTP 409. El mensaje mantiene el límite de 1 000 caracteres.

También se puede continuar por chat manual o por voz. Con un único campo de texto, el agente acepta un valor aislado cuando puede interpretarlo con seguridad. Para varios campos, usa etiquetas como «Origen: Surco, capacidad: 3». Un saludo, una confirmación o una consulta informativa no se toma como valor. Una nueva solicitud incompatible invalida el formulario anterior.

Un envío rechazado por su formato o sus valores conserva el formulario activo. Si durante la continuación ocurre un fallo antes de intentar preparar la propuesta, el formulario conserva su identificador. Si el resultado de la preparación queda incierto, el servidor invalida ese identificador y devuelve `advertencia.codigo: "PREPARATION_RESULT_UNCERTAIN"`.

El adaptador de Angular lee primero `aclaracion` y construye los controles a partir de sus campos. La vista muestra las etiquetas de los selectores y el cuerpo HTTP lleva sus valores. El reconocimiento textual sigue disponible para respuestas antiguas sin descriptor. [PR #39 de Angular](https://github.com/Ian-1232/uniride-frontend/pull/39)

## Fixtures de respuesta

Los UUID son ejemplos. En una conversación real se toman del servidor. Los siguientes fragmentos pueden ponerse sobre un `ChatResponse` de pruebas con las listas habituales.

### Crear una ruta con origen ya indicado

```json
{
  "message": "Indica puntos de referencia, capacidad máxima.",
  "conversationId": "ee2a58bc-82e1-46cf-86c3-e286316a87d1",
  "aclaracion": {
    "version": 1,
    "id": "cead719a-918b-4555-8d27-2fbdf6421720",
    "operacion": "crearRuta",
    "contexto": "Crear una ruta",
    "campos": [
      {"id": "referencias", "etiqueta": "Puntos de referencia", "tipo": "textarea", "obligatorio": true},
      {"id": "capacidad", "etiqueta": "Capacidad máxima", "tipo": "number", "obligatorio": true, "minimo": 1, "maximo": 2147483647}
    ],
    "respuestaBreve": "campo"
  }
}
```

Ejemplo de envío del formulario anterior.

```json
{
  "conversationId": "ee2a58bc-82e1-46cf-86c3-e286316a87d1",
  "message": "Datos de aclaración cead719a-918b-4555-8d27-2fbdf6421720: {\"referencias\":\"Parque central\",\"capacidad\":3}"
}
```

El mismo formulario admite una continuación parcial.

```json
{
  "conversationId": "ee2a58bc-82e1-46cf-86c3-e286316a87d1",
  "message": "Datos de aclaración cead719a-918b-4555-8d27-2fbdf6421720: {\"capacidad\":3}"
}
```

```json
{
  "message": "Indica puntos de referencia.",
  "conversationId": "ee2a58bc-82e1-46cf-86c3-e286316a87d1",
  "aclaracion": {
    "version": 1,
    "id": "c883be48-2639-4b78-a5cf-7b6c8325bda6",
    "operacion": "crearRuta",
    "contexto": "Crear una ruta",
    "campos": [
      {"id": "referencias", "etiqueta": "Puntos de referencia", "tipo": "textarea", "obligatorio": true}
    ],
    "respuestaBreve": "valor"
  }
}
```

El usuario puede escribir «Parque central» en la misma conversación, dictarlo por voz o completar el nuevo formulario.

```json
{
  "conversationId": "ee2a58bc-82e1-46cf-86c3-e286316a87d1",
  "message": "Datos de aclaración c883be48-2639-4b78-a5cf-7b6c8325bda6: {\"referencias\":\"Parque central\"}"
}
```

### Identificar una ruta por origen o destino

```json
{
  "message": "Indica el origen o el destino de la ruta que quieres eliminar.",
  "conversationId": "ee2a58bc-82e1-46cf-86c3-e286316a87d1",
  "aclaracion": {
    "version": 1,
    "id": "5fdc652f-ae2f-4bbc-a6cc-e6c12ee4baf1",
    "operacion": "eliminarRuta",
    "contexto": "Identificar la ruta que quieres eliminar",
    "campos": [
      {"id": "origenActual", "etiqueta": "Origen actual", "tipo": "text", "obligatorio": false, "longitudMaxima": 255, "ayuda": "Completa al menos uno de estos campos."},
      {"id": "destinoActual", "etiqueta": "Destino actual", "tipo": "text", "obligatorio": false, "longitudMaxima": 255, "ayuda": "Completa al menos uno de estos campos."}
    ],
    "alMenosUno": ["origenActual", "destinoActual"],
    "respuestaBreve": "campo"
  }
}
```

### Preferencia para solicitar un cupo

```json
{
  "message": "Indica cómo prefieres compensar: DINERO, GASOLINA o FAVOR.",
  "conversationId": "ee2a58bc-82e1-46cf-86c3-e286316a87d1",
  "aclaracion": {
    "version": 1,
    "id": "e428b153-5794-4400-86e0-878002db5047",
    "operacion": "solicitarUnirse",
    "contexto": "Solicitar un cupo",
    "campos": [{
      "id": "compensacion", "etiqueta": "Tipo de compensación", "tipo": "select", "obligatorio": true,
      "opciones": [
        {"valor": "DINERO", "etiqueta": "Dinero"},
        {"valor": "GASOLINA", "etiqueta": "Gasolina"},
        {"valor": "FAVOR", "etiqueta": "Favor"}
      ]
    }],
    "opcionesRapidas": true,
    "respuestaBreve": "campo"
  }
}
```

## Seleccionar una ruta para crear un viaje

El campo `ruta` es un selector del contrato v1. Cada `valor` es una referencia opaca emitida por Java para la aclaración activa. Envía la referencia exactamente como aparece en `opciones`, con el UUID del formulario vigente. El servidor comprueba que la opción fue ofrecida y que la ruta sigue siendo propia y elegible. Un valor ausente de las opciones del formulario activo devuelve HTTP 400. Un UUID de formulario reemplazado devuelve HTTP 409 `CLARIFICATION_EXPIRED`. La confirmación conserva el mecanismo habitual.

Las páginas muestran hasta diez opciones. Cuando hay más resultados, el mensaje indica que la lista es parcial. Escribe «siguiente página» o «página anterior» en el mismo `conversationId` para recorrerlas. También puedes usar «Origen: Surco» o «Desde Surco» para buscar por texto o voz. Si la búsqueda es ambigua, se ofrecen las coincidencias. Los campos válidos del borrador se mantienen al buscar, paginar o corregir la ruta. Cuando se emite otro descriptor, utiliza el nuevo UUID y sus opciones.

Por voz, el agente lee las primeras cinco opciones de la página. Puedes elegir una opción vigente diciendo «ruta número 2» cuando la etiqueta comienza con `2.`.

### Solicitud sin ruta, fecha ni hora

```json
{"message":"Quiero crear un viaje"}
```

```json
{
  "message": "Selecciona una de tus rutas registradas. Indica la fecha de salida. Indica la hora de salida.",
  "conversationId": "ff4291eb-85b3-4e85-93a4-f9b745340452",
  "aclaracion": {
    "version": 1,
    "id": "6c9bace5-d0b3-4d34-a1cb-5d45b0ea57a3",
    "operacion": "crearViaje",
    "contexto": "Crear un viaje",
    "campos": [
      {
        "id": "ruta",
        "etiqueta": "Ruta registrada",
        "tipo": "select",
        "obligatorio": true,
        "opciones": [
          {"valor": "22e75375-0978-45ea-8535-bb459262cf32", "etiqueta": "1. Surco → UPC Monterrico · Capacidad 3 · Parque central"},
          {"valor": "0c262523-1dd1-42dd-9bb0-a48fe4a6d4bc", "etiqueta": "2. Barranco → UPC Villa · Capacidad 4 · Plaza de Barranco"}
        ]
      },
      {"id": "fecha", "etiqueta": "Fecha", "tipo": "date", "obligatorio": true},
      {"id": "hora", "etiqueta": "Hora", "tipo": "time", "obligatorio": true}
    ],
    "respuestaBreve": "campo"
  }
}
```

### Elegir una ruta y enviar datos parciales

Elige la ruta y envía la fecha. En este ejemplo, el formulario siguiente solicita únicamente la hora.

```json
{
  "conversationId": "ff4291eb-85b3-4e85-93a4-f9b745340452",
  "message": "Datos de aclaración 6c9bace5-d0b3-4d34-a1cb-5d45b0ea57a3: {\"ruta\":\"22e75375-0978-45ea-8535-bb459262cf32\",\"fecha\":\"2026-12-10\"}"
}
```

```json
{
  "conversationId": "ff4291eb-85b3-4e85-93a4-f9b745340452",
  "message": "Indica hora.",
  "aclaracion": {
    "version": 1,
    "id": "ee009b7e-63ee-46fd-95be-282778f935cb",
    "operacion": "crearViaje",
    "contexto": "Crear un viaje",
    "campos": [
      {"id": "hora", "etiqueta": "Hora", "tipo": "time", "obligatorio": true}
    ],
    "respuestaBreve": "campo"
  }
}
```

Envía la hora con el nuevo UUID. El servidor conserva la ruta exacta y la fecha.

```json
{
  "conversationId": "ff4291eb-85b3-4e85-93a4-f9b745340452",
  "message": "Datos de aclaración ee009b7e-63ee-46fd-95be-282778f935cb: {\"hora\":\"07:00\"}"
}
```

Con datos válidos, llega una propuesta pendiente de confirmación.

```json
{
  "conversationId": "ff4291eb-85b3-4e85-93a4-f9b745340452",
  "operacion": {
    "estado": "CONFIRMACION_REQUERIDA",
    "id": null,
    "detalle": "Programar un viaje desde Surco hacia UPC Monterrico el 2026-12-10 a las 07:00, capacidad 3. Referencias: Parque central. ¿Confirmas esta operación?",
    "confirmacion": {
      "id": "c886e9b6-b416-4676-9f77-34fec2c238d0",
      "tipo": "CREAR_VIAJE",
      "estado": "PENDIENTE",
      "resumen": "Programar un viaje desde Surco hacia UPC Monterrico el 2026-12-10 a las 07:00, capacidad 3. Referencias: Parque central.",
      "parametros": {"fecha": "2026-12-10", "hora": "07:00:00"},
      "expira": "2026-11-23T17:10:00Z",
      "resultado": null
    }
  }
}
```

### Corregir un origen inexistente

Con «Crea un viaje desde Lurín el 10 de diciembre de 2026 a las 7 de la mañana», si el conductor carece de una ruta propia desde Lurín, recibe las rutas vigentes. Como el horario ya se recibió, el selector es el único campo.

```json
{
  "message": "No encontré una ruta propia con esos datos. Selecciona una de tus rutas registradas.",
  "conversationId": "b0d0c397-2f78-4814-af53-7ac0cb9e6b19",
  "aclaracion": {
    "version": 1,
    "id": "728a3aad-033c-4c94-ba9b-58538be4d3ed",
    "operacion": "crearViaje",
    "contexto": "Crear un viaje",
    "campos": [{
      "id": "ruta",
      "etiqueta": "Ruta registrada",
      "tipo": "select",
      "obligatorio": true,
      "opciones": [
        {"valor": "19252154-3384-46d4-abce-0d35ef31e4c5", "etiqueta": "1. Surco → UPC Monterrico · Capacidad 3 · Parque central"}
      ]
    }],
    "opcionesRapidas": true,
    "respuestaBreve": "valor"
  }
}
```

### Distinguir rutas con el mismo origen y destino

Si «Crea un viaje desde Surco el 10 de diciembre de 2026 a las 7 de la mañana» coincide con varias rutas propias, el formulario muestra esas coincidencias. Cada referencia representa una ruta concreta, incluso si el origen, destino y capacidad son iguales.

```json
{
  "message": "Coinciden varias de tus rutas. Selecciona una de tus rutas registradas.",
  "conversationId": "acf33180-8e74-4eed-ab8e-0317eb17d2fd",
  "aclaracion": {
    "version": 1,
    "id": "658ff969-eddf-4083-9acc-39626ed499ad",
    "operacion": "crearViaje",
    "contexto": "Crear un viaje",
    "campos": [{
      "id": "ruta",
      "etiqueta": "Ruta registrada",
      "tipo": "select",
      "obligatorio": true,
      "opciones": [
        {"valor": "e1fdc837-3e2b-4838-8776-3c34980e17bd", "etiqueta": "1. Surco → UPC Monterrico · Capacidad 3 · Parque central"},
        {"valor": "944c514d-1969-431b-a286-225abaf9e363", "etiqueta": "2. Surco → UPC Monterrico · Capacidad 3 · Óvalo Higuereta"}
      ]
    }],
    "opcionesRapidas": true,
    "respuestaBreve": "valor"
  }
}
```

Si la ruta seleccionada cambió o dejó de estar disponible, el agente solicita una nueva elección entre las rutas vigentes. Si el conductor carece de rutas elegibles, recibe una indicación para registrar una ruta.

## Operaciones

| Operación | Campos que puede solicitar |
|---|---|
| `crearRuta` | Origen, referencias y capacidad. Nota de contribución si la proporcionada necesita corrección |
| `editarRuta` | Identificación por origen o destino actual, y valores que se quiere cambiar |
| `eliminarRuta` | Identificación por origen o destino actual |
| `crearViaje` | Ruta propia, fecha y hora |
| `reprogramarViaje` | Identificación de la salida, nueva fecha y nueva hora |
| `solicitarUnirse` | Datos de la salida y preferencia entre `DINERO`, `GASOLINA` y `FAVOR` |
| `cancelarSolicitud` | Filtros que distingan solicitudes pendientes propias |
| `registrarMetodoCompensacion` | Tipo y descripción |
| `actualizarMetodoCompensacion` | Identificación del método y nuevo tipo o descripción |
| `cambiarEstadoMetodoCompensacion` | Identificación del método a activar o desactivar |
| `eliminarMetodoCompensacion` | Identificación del método por tipo o descripción |
| `sugerirContribucionViaje` | Filtros de la salida o cantidad de pasajeros |
| `prepararDescargaHistorialPdf` | Período, extremos de fechas o rol en el historial |

Las aclaraciones se generan en Java. Los turnos locales aprovechan los datos ya identificados, y cuando una herramienta del modelo detecta un argumento faltante produce el mismo tipo de respuesta. El flujo mantiene el presupuesto por turno y la preparación y confirmación separadas. [Flujo completo del agente](agente_flujo.md)
