# Documento sobre como integrar un agente con Spring AI

## Requisitos

- Java 21, Maven, JWT Secret
- Una clave de algun servicio de IA (Gemini)

## Configuracion

- Dependencias en el `pom.xml`
- Configuración del servicio externo de IA en `application.yaml`

## Buenas Prácticas

### Implementar DTOs del chat: ChatRequest, ViajeSummary, ChatResponse, etc. (Todo dentro de un directorio: `uniride/agent/dto/`)

### Configurar ChatClient dentro de `uniride/agent/AgentConfig.java`.

Ejemplo de codigo (de otro proyecto):

```java
package com.hampcode.ticketsales.agent;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
@Configuration
public class AgentConfig {
  private static final String SYSTEM = """

      # ROL

      Eres el asistente de una plataforma de venta de entradas.

      # REGLAS

      - Responde en español, breve y amable.
      - Datos reales: usa siempre las tools. Nunca inventes eventos,
      fechas, precios ni cupos.
      - El usuario escribe informal (abreviaciones, errores): interpreta
      su intención y no exijas nombres exactos.
      - Al buscar por nombre usa una palabra clave corta, sin tildes y tal
      como la escribió el usuario.
      - Si falta un dato para continuar, pregúntalo.
      - Si no hay resultados, dilo con claridad.

      # FORMATO DE RESPUESTA

      - Responde siempre y solo con el JSON pedido: nada de texto fuera de
      él, ni siquiera una pregunta.
      - message: la respuesta completa para el usuario. Nunca vacío.
      - events: lo que devolvieron las tools. Vacío si no usaste ninguna.
      """;
      @Bean
      ChatClient chatClient(ChatClient.Builder builder) {
          return builder
          .defaultSystem(SYSTEM)
          .build();
          }
  }

```

El prompt tiene una estructura fija de secciones: ROL, REGLAS y FORMATO DE RESPUESTA. Cualquier grupo puede reutilizarla cambiando solo el contenido de cada sección. No hace falta listar jergas: los modelos ya entienden el lenguaje informal, basta con indicarlo en las reglas.

### Servicio y controller del agente (dentro de `/uniride/agent/`)

AgentService.java (ejemplo de otro proyecto)

```java
  package com.hampcode.ticketsales.agent;
  import com.hampcode.ticketsales.agent.dto.ChatResponse;
  import lombok.RequiredArgsConstructor;
  import org.springframework.ai.chat.client.ChatClient;
  import org.springframework.stereotype.Service;

  @Service
  @RequiredArgsConstructor
  public class AgentService {
      private final ChatClient chatClient;

      public ChatResponse chat(String message) {
          // El agente responde con un objeto (salida estructurada), no con texto
          libre
          return chatClient.prompt()
          .user(message)
          .call()
          .entity(ChatResponse.class);
          }
  }
```

.entity(ChatResponse.class) reemplaza a .content(): la respuesta ya no es un String sino un objeto

AgentController.java

```java
package com.hampcode.ticketsales.agent;
import com.hampcode.ticketsales.agent.dto.ChatRequest;
import com.hampcode.ticketsales.agent.dto.ChatResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/agent")
@RequiredArgsConstructor
public class AgentController {
    private final AgentService agentService;
    @PostMapping("/chat")
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        return agentService.chat(request.message());
    }
}
```

El controller solo recibe la petición y responde: la lógica del agente está en el servicio, como en el resto del proyecto.

### Tools: Herramientas que el Agente podra utilizar (se implementa dentro de `/uniride/agent/tools/`)

#### EventAgentTools.java (de otro proyecto)

```java
package com.hampcode.ticketsales.agent.tools;
import com.hampcode.ticketsales.agent.dto.EventSummary;
import com.hampcode.ticketsales.dto.response.EventResponseDTO;
import com.hampcode.ticketsales.service.EventService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
@RequiredArgsConstructor
public class EventAgentTools {
    private final EventService eventService;
    @Tool(description = """
        Busca eventos por ciudad o por parte del nombre.
        Úsala siempre que el usuario pregunte por eventos.
    """)
    public List<EventSummary> searchEvents(
        @ToolParam(description = "Ciudad, solo si el usuario la menciona", required = false) String city,
        @ToolParam(description = "Parte del nombre del evento, solo si el usuario la menciona", required = false) String name) {
            return eventService.searchEvents(name, city, null, null, null, null, PageRequest.of(0, 5))
                    .content()
                    .stream()
                    .map(this::toSummary)
                    .toList();
    }

    private EventSummary toSummary(EventResponseDTO e) {
        return new EventSummary(
                e.id(), e.name(), e.date(), e.city(),
                e.basePrice(), e.availableCapacity());
    }
}
```

La tool devuelve una lista de EventSummary: ese resultado vuelve al modelo, que lo usa para redactar el mensaje. La ciudad y el nombre son opcionales y el nombre se busca de forma parcial, así el usuario no necesita escribirlos exacto

#### Registrar la tool (Dentro de `/uniride/agent/AgentConfig.java`)

```java
package com.hampcode.ticketsales.agent;
import com.hampcode.ticketsales.agent.tools.EventAgentTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AgentConfig {
    private static final String SYSTEM = """
    # ROL
    Eres el asistente de una plataforma de venta de entradas.
    # REGLAS
    - Responde en español, breve y amable
    - Datos reales: usa siempre las tools. Nunca inventes eventos,
    fechas, precios ni cupos.
    - El usuario escribe informal (abreviaciones, errores): interpreta
    su intención y no exijas nombres exactos.
    - Al buscar por nombre usa una palabra clave corta, sin tildes y tal
    como la escribió el usuario.
    - Si falta un dato para continuar, pregúntalo.
    - Si no hay resultados, dilo con claridad.
    # HERRAMIENTAS
    - searchEvents: cuando pregunten qué eventos hay, por ciudad o por
    nombre.
    # FORMATO DE RESPUESTA
    - Responde siempre y solo con el JSON pedido: nada de texto fuera de
    él, ni siquiera una pregunta.
    - message: la respuesta completa para el usuario. Nunca vacío.
    - events: lo que devolvieron las tools. Vacío si no usaste ninguna.
    """;
    @Bean
    ChatClient chatClient(ChatClient.Builder builder, EventAgentTools tools) {
        return builder
            .defaultSystem(SYSTEM)
            .defaultTools(tools)
            .build();
    }
}
```

### Modificar `GlobalExceptionHandler.java`

```java
import tools.jackson.core.JacksonException;
...
// el modelo de IA respondió texto en vez del JSON esperado
@ExceptionHandler(JacksonException.class)
public ResponseEntity<ErrorResponseDTO> handleAiFormat(JacksonException ex) {
    return build(HttpStatus.BAD_GATEWAY, "El agente no pudo armar la respuesta, intenta de nuevo");
}
```

A veces el modelo responde con texto en vez del JSON esperado. Spring AI lanza entonces una JacksonException y el GlobalExceptionHandler que ya existe la convierte en un error 502 con el mismo formato de los demás errores. Agrega el import junto a los demás y el método antes de build.

### Tools de lectura por nombre (en `/uniride/agent/tools/EventAgentTools.java`)

Ejemplo de otro proyecto:

```java
package com.hampcode.ticketsales.agent.tools;
import com.hampcode.ticketsales.agent.dto.EventSummary;
import com.hampcode.ticketsales.dto.response.EventResponseDTO;
import com.hampcode.ticketsales.service.EventService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
@RequiredArgsConstructor
public class EventAgentTools {
    private final EventService eventService;
    @Tool(description = """Busca eventos por ciudad o por parte del nombre. Sin filtros devuelve los próximos eventos. Úsala siempre que el usuario pregunte por eventos. """)
    public List<EventSummary> searchEvents(
        @ToolParam(description = "Ciudad, solo si el usuario la menciona", required = false) String city,
        @ToolParam(description = "Parte del nombre del evento, solo si el usuario la menciona", required = false) String name) {
            return find(name, city);
    }
    @Tool(description = """Consulta los cupos y el precio de un evento a partir de su nombre. Si devuelve más de un evento, pregunta al usuario cuál le interesa.""")
    public List<EventSummary> checkAvailability(@ToolParam(description = "Nombre del evento, o parte de él") String name,
                                                @ToolParam(description = "Ciudad, solo si el usuario la menciona", required = false) String city) {
        return find(name, city);
    }

    // El usuario no conoce ids: el servidor resuelve el evento a partir del nombre y la ciudad
    private List<EventSummary> find(String name, String city) {
        return eventService.searchEvents(
            name, city, null, null, null, null,
            PageRequest.of(0, 5))
                .content()
                .stream()
                .map(this::toSummary)
                .toList();
    }

    private EventSummary toSummary(EventResponseDTO e) {
        return new EventSummary(
            e.id(), e.name(), e.date(), e.city(),
            e.basePrice(), e.availableCapacity());
    }
}
```

El participante no conoce el id de un evento, por eso ninguna tool lo pide: checkAvailability recibe el nombre (y opcionalmente la ciudad) y el servidor busca el evento. Si hay variosque coinciden, la tool los devuelve todos y el agente pregunta cuál.

### Tools de Escritura (en `/uniride/agent/tools/EventAgentTools.java`)

```java
package com.hampcode.ticketsales.agent.tools;
import com.hampcode.ticketsales.agent.dto.EventSummary;
import com.hampcode.ticketsales.agent.dto.PurchaseResult;
import com.hampcode.ticketsales.dto.request.TicketRequestDTO;
import com.hampcode.ticketsales.dto.response.EventResponseDTO;
import com.hampcode.ticketsales.dto.response.TicketResponseDTO;
import com.hampcode.ticketsales.service.EventService;
import com.hampcode.ticketsales.service.TicketService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
@RequiredArgsConstructor
public class EventAgentTools {
    private final EventService eventService;
    private final TicketService ticketService;
    @Tool(description = ""
            "
            Busca eventos por ciudad o por parte del nombre.Sin filtros devuelve los próximos eventos.Úsala siempre que el usuario pregunte por eventos.
            ""
            ")
            public List < EventSummary > searchEvents(
                @ToolParam(description = "Ciudad, solo si el usuario la menciona",
                    required = false) String city,
                @ToolParam(description = "Parte del nombre del evento, solo si el
                    usuario la menciona ", required = false)
                    String name) {
                    return find(name, city);
                }
                @Tool(description = ""
                    "
                    Consulta los cupos y el precio de un evento a partir de su nombre.Si devuelve más de un evento, pregunta al usuario cuál le interesa.
                    ""
                    ")
                    public List < EventSummary > checkAvailability(
                        @ToolParam(description = "Nombre del evento, o parte de él") String name,
                        @ToolParam(description = "Ciudad, solo si el usuario la menciona",
                            required = false) String city) {
                        return find(name, city);
                    }
                    @Tool(description = ""
                        "
                        Inicia la compra de UNA entrada para el evento indicado por nombre y / o ciudad.NO registra la entrada: devuelve CONFIRMATION_REQUIRED con el evento y el precio para que el usuario confirme.Si varios eventos coinciden devuelve AMBIGUOUS.Si el usuario no dijo ningún evento devuelve MISSING_EVENT.
                        ""
                        ")
                        public PurchaseResult requestTicket(
                            @ToolParam(description = "Nombre del evento, o parte de él",
                                required = false) String name,
                            @ToolParam(description = "Ciudad, solo si el usuario la menciona",
                                required = false) String city) {
                            return buy(name, city, false);
                        }
                        @Tool(description = ""
                            "
                            Registra la entrada cuando el usuario YA confirmó la compra(dijo sí, confirmo, dale).Es la única forma de registrar una entrada.Devuelve PENDING si quedó registrada.
                            ""
                            ")
                            public PurchaseResult confirmTicket(
                                @ToolParam(description = "Nombre del evento que el usuario
                                    confirmó ", required = false) String name,
                                    @ToolParam(description = "Ciudad, solo si el usuario la menciona",
                                        required = false) String city) {
                                    return buy(name, city, true);
                                }
                                private PurchaseResult buy(String name, String city, boolean confirmed) {
                                    requireParticipant();
                                    if (isBlank(name) && isBlank(city)) {
                                        return new PurchaseResult(null, null, null, "MISSING_EVENT");
                                    }
                                    List < EventSummary > matches = find(name, city);
                                    EventSummary event = pickEvent(matches, name);
                                    if (event == null) {
                                        return new PurchaseResult(null, null, null, matches.isEmpty() ?
                                            "NOT_FOUND" : "AMBIGUOUS");
                                    }
                                    if (!confirmed) {
                                        return new PurchaseResult(null, event.name(), event.price(),
                                            "CONFIRMATION_REQUIRED");
                                    }
                                    TicketResponseDTO t = ticketService
                                        .purchaseTicket(new TicketRequestDTO(event.id()));
                                    return new PurchaseResult(
                                        t.id(), t.eventName(), t.pricePaid(), t.status());
                                }
                                // El usuario no conoce ids: el servidor resuelve el evento a partir del
                                nombre y la ciudad private List < EventSummary > find(String name, String city) {
                                    return eventService.searchEvents(
                                            name, city, null, null, null, null,
                                            PageRequest.of(0, 5))
                                        .content()
                                        .stream()
                                        .map(this::toSummary)
                                        .toList();
                                }
                                // Un solo resultado: ese. Varios: solo si uno coincide exactamente con el
                                nombre dicho private EventSummary pickEvent(List < EventSummary > matches, String name) {
                                    if (matches.size() == 1) {
                                        return matches.get(0);
                                    }
                                    if (isBlank(name)) {
                                        return null;
                                    }
                                    List < EventSummary > exact = matches.stream()
                                    filter(e - > e.name().equalsIgnoreCase(name.trim()))
                                        .toList();
                                    return exact.size() == 1 ? exact.get(0) : null;
                                }
                                private boolean isBlank(String s) {
                                    return s == null || s.isBlank();
                                }
                                private EventSummary toSummary(EventResponseDTO e) {
                                    return new EventSummary(
                                        e.id(), e.name(), e.date(), e.city(),
                                        e.basePrice(), e.availableCapacity());
                                }
                                private void requireParticipant() {
                                    Authentication auth = SecurityContextHolder
                                        .getContext().getAuthentication();
                                    boolean isParticipant = auth != null &&
                                        auth.getAuthorities().stream()
                                        .anyMatch(a - > a.getAuthority().equals("ROLE_PARTICIPANT"));
                                    if (!isParticipant) {
                                        throw new AccessDeniedException(
                                            "Solo un participante puede comprar entradas");
                                    }
                                }
                            }

```

La compra son dos tools con nombres que equivalen a lo que dice el usuario: requestTicket inicia la compra pero NO registra nada (devuelve CONFIRMATION_REQUIRED con el evento y el precio) y confirmTicket registra la entrada cuando el usuario confirma.
Resultados posibles: PENDING (registrada), CONFIRMATION_REQUIRED, AMBIGUOUS (varios eventos coinciden), NOT_FOUND y MISSING_EVENT (no dijo cuál). La validación de rol es lo primero que se ejecuta: si quien llama no es PARTICIPANT, ni siquiera busca eventos.
Aprobación de la compra. El flujo de dos pasos pide que el usuario apruebe: requestTicket no registra nada y solo confirmTicket lo hace. Hoy ese orden lo sigue el modelo porque el prompt (FLUJO DE COMPRA) se lo indica; el servidor solo garantiza el rol PARTICIPANT, y la entrada queda en estado PENDING (sin pagar). En un sistema real la aprobación debería ser un botón en la pantalla que llame directamente a POST /tickets, así ninguna respuesta del modelo puede registrar una compra por sí sola.

### Memoria de conversación y system prompt (Modificar en `/uniride/agent/AgentConfig.java`)

```java
package com.hampcode.ticketsales.agent;
import com.hampcode.ticketsales.agent.tools.EventAgentTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
@Configuration
public class AgentConfig {
    private static final String SYSTEM = ""
    "
    # ROL
    Eres el asistente de una plataforma de venta de entradas.
    # REGLAS
        -
        Responde en español, breve y amable. -
        Datos reales: usa siempre las tools.Nunca inventes eventos,
        fechas, precios ni cupos. -
        El usuario escribe informal(abreviaciones, errores): interpreta
    su intención y no exijas nombres exactos. -
        Nunca pidas ids: identifica el evento por el nombre o la ciudad
    que mencione el usuario. -
        Al buscar por nombre usa una palabra clave corta, sin tildes y tal
    como la escribió el usuario. -
        Si falta un dato para continuar, pregúntalo. -
        Si no hay resultados, dilo con claridad. -
        Nunca digas que hiciste algo(comprar, consultar) si no llamaste a
    la tool que lo hace. -
        Llama a las tools en cada mensaje que lo requiera, aunque ya lo
    hayas hecho antes en la conversación.
    # HERRAMIENTAS
        -
        searchEvents: cuando pregunten qué eventos hay, por ciudad o por
    nombre. -
        checkAvailability: cuando pregunten por los cupos o el precio de
    un evento. -
        requestTicket: cuando el usuario quiera comprar una entrada.No la
    registra. -
        confirmTicket: cuando el usuario confirme la compra.Es lo único
    que registra la entrada.
    # FLUJO DE COMPRA
    1. Llama a requestTicket, aunque el usuario no haya dicho el evento.
    2. MISSING_EVENT: pregunta qué evento quiere.
    3. AMBIGUOUS: llama a searchEvents, lista las opciones y pregunta
    cuál quiere.
    4. CONFIRMATION_REQUIRED: pide confirmación mostrando el nombre del
    evento y el precio.
    5. Si el usuario confirma, aunque sea con una sola palabra(sí,
            confirmo, dale):
        llama a confirmTicket con el nombre del evento de la
    conversación.
    Sin esa llamada la entrada NO se registra.
    # FORMATO DE RESPUESTA
        -
        Responde siempre y solo con el JSON pedido: nada de texto fuera de
    él, ni siquiera una pregunta. -
        message: la respuesta completa para el usuario.Nunca vacío. -
        events: lo que devolvieron las tools.Vacío si no usaste ninguna. -
        purchase: copia completo(con todos sus campos) el resultado de
    requestTicket o confirmTicket.null si no los llamaste. -
        En message informa según lo que devolvió la tool: PENDING
    significa entrada registrada.
    # LÍMITES
        -
        Una entrada por vez. -
        No puedes pagar ni reembolsar.
    ""
    ";
    @Bean
    ChatClient chatClient(ChatClient.Builder builder,
        EventAgentTools tools) {
        // Memoria: guarda los últimos 20 mensajes de cada conversación
        var memory = MessageWindowChatMemory.builder()
            .maxMessages(20)
            .build();
        return builder
            .defaultSystem(SYSTEM)
            .defaultTools(tools)
            .defaultAdvisors(MessageChatMemoryAdvisor.builder(memory).build())
            .build();
    }
}
```

La memoria es un advisor: MessageWindowChatMemory guarda los últimos 20 mensajes de cada conversación y MessageChatMemoryAdvisor los agrega a cada llamada. Gracias a eso el agente entiende un "sí, confirmo" o un "el de jazz" que depende del mensaje anterior.
La memoria vive en RAM: al reiniciar la app se pierde. El system prompt mantiene la misma estructura de secciones del Laboratorio 01 (ROL, REGLAS, HERRAMIENTAS, FLUJO DE COMPRA, FORMATO DE RESPUESTA y LÍMITES): para otro proyecto se cambia el contenido de cada sección, no la estructura.

#### Modificar (`/uniride/agent/AgentService.java`)

```java
package com.hampcode.ticketsales.agent;
import com.hampcode.ticketsales.agent.dto.ChatResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Service;
@Service
@RequiredArgsConstructor
public class AgentService {
    private final ChatClient chatClient;
    // username sale del JWT: cada usuario tiene su propia conversación y nadie
    lee la de otro
    public ChatResponse chat(String username, String message) {
        return chatClient.prompt()
            .user(message)
            .advisors(a - > a.param(ChatMemory.CONVERSATION_ID, username))
            .call()
            .entity(ChatResponse.class);
    }
}
```

El username sale del JWT (auth.getName()): cada usuario tiene su propia conversación y nadie puede leer la de otro

#### Modificar (`/uniride/agent/AgentController.java`)

```java
package com.hampcode.ticketsales.agent;
import com.hampcode.ticketsales.agent.dto.ChatRequest;
import com.hampcode.ticketsales.agent.dto.ChatResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
@RestController
@RequestMapping("/agent")
@RequiredArgsConstructor
public class AgentController {
    private final AgentService agentService;
    @PostMapping("/chat")
    public ChatResponse chat(@Valid @RequestBody ChatRequest request,
        Authentication auth) {
        return agentService.chat(auth.getName(), request.message());
    }
}
```

El controller solo agrega el usuario autenticado y sigue recibiendo y respondiendo.

## Estructura del directorio para el agente

```
|_ uniride
|   |_ agent/
|       |_ dto/
|       |_ tools/
|           |_ AgentTools.java
|       |_ AgentService.java
|       |_ AgentController.java
|       |_ AgentConfig.java
```

## Objetivos para este proyecto:

1. Que el pasajero pueda preguntar por viajes en X dia en X horas...
2. Que el pasajero / conductor consulte sobre sus viajes.
3. Que el conductor pueda consultar sobre sus rutas.
4. Que el pasajero / conductor consulte sobre sus metodos de compensacion
5. Que el conductor pueda crear una ruta
6. Que el conductor pueda crear un viaje
7. Que el pasajero se pueda unir a un viaje
8. Que el pasajero / conductor pueda registrar metodos de compensacion
