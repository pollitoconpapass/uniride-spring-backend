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

El [PR #39 de Angular](https://github.com/Ian-1232/uniride-frontend/pull/39) ya dispone del formulario genérico, los tipos de campo y el transporte de chat. Su adaptador necesita leer `aclaracion` antes del reconocimiento textual y, para este formato, serializar el mensaje anterior. Puede conservar el flujo de compatibilidad para servidores que todavía no devuelven el campo. La previsualización puede mostrar los valores con sus etiquetas mientras el cuerpo HTTP usa el identificador del formulario.

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
