package dev.sandipchitale.ai.spring_ai_mcp_server;

import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpRequestHandler;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.server.transport.WebMvcStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.DefaultMcpStreamableServerSessionFactory;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Service;
import org.springframework.util.ReflectionUtils;
import reactor.core.publisher.Mono;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.*;

@SpringBootApplication
public class SpringAiMcpServerApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(SpringAiMcpServerApplication.class);

        app.setBanner((environment, sourceClass, out) -> out.println("Spring AI Streamable HTTP MCP Server Application"));
        ConfigurableApplicationContext ignore = app.run(args);
    }

    @Bean
    CommandLineRunner mcpServerInterceptor(WebMvcStreamableServerTransportProvider webMvcStreamableServerTransportProvider) {
        return args -> {
            Field sessionFactoryField = ReflectionUtils.findField(webMvcStreamableServerTransportProvider.getClass(), "sessionFactory");
            if (sessionFactoryField != null) {
                sessionFactoryField.setAccessible(true);
                DefaultMcpStreamableServerSessionFactory defaultMcpStreamableServerSessionFactory =
                        (DefaultMcpStreamableServerSessionFactory) sessionFactoryField.get(webMvcStreamableServerTransportProvider);
                Field requestHandlersField = ReflectionUtils.findField(defaultMcpStreamableServerSessionFactory.getClass(), "requestHandlers");
                if (requestHandlersField != null) {
                    requestHandlersField.setAccessible(true);
                    Map<String, McpRequestHandler<?>> requestHandlers =
                            (Map<String, McpRequestHandler<?>>) requestHandlersField.get(defaultMcpStreamableServerSessionFactory);
                    final McpRequestHandler<?> originalMcpRequestHandler = requestHandlers.get(McpSchema.METHOD_TOOLS_CALL);
                    final Set<String> seenSessionIds = new HashSet<>();
                    requestHandlers.put(McpSchema.METHOD_TOOLS_LIST, (McpAsyncServerExchange exchange, Object params) -> {
                        // Client supports sampling
                        if (exchange.getClientCapabilities().sampling() != null) {
                            String sessionId = exchange.sessionId();
                            // Unseen session - first tool call
                            if (!seenSessionIds.contains(sessionId)) {
                                seenSessionIds.add(sessionId);
                                // Create and use the ToolContext for sampling only once.
                                ToolContext toolContext = new ToolContext(Map.of("exchange", new McpSyncServerExchange(exchange)));
                                McpToolUtils.getMcpExchange(toolContext).ifPresent(mcpExchange -> {
                                    if (exchange.getClientCapabilities().sampling() != null) {
                                        String ping = "Ping: " + LocalDateTime.now();
                                        System.out.println("Sending sampling request to LLM: " + ping + " to MCP Client sessionId: " + sessionId);
                                        var messageRequestBuilder = McpSchema.CreateMessageRequest.builder()
                                                .messages(List.of(new McpSchema.SamplingMessage(McpSchema.Role.USER, new McpSchema.TextContent(ping))));
                                        var opeAiLlmMessageRequest = messageRequestBuilder
                                                .modelPreferences(McpSchema.ModelPreferences.builder().build())
                                                .build();
                                        McpSchema.CreateMessageResult response = mcpExchange.createMessage(opeAiLlmMessageRequest);
                                        System.out.println("Sampling response from LLM: "
                                                + ((McpSchema.TextContent) response.content()).text() + " from  MCP Client sessionId: " + sessionId);
                                    }
                                });
                            }
                        }
                        return (Mono<Object>) originalMcpRequestHandler.handle(exchange, params);
                    });
                }
            }
        };
    }

    public record Person(int id, String name) {
    }

    @Service
    public static class PersonService {
        private final List<Person> people = new ArrayList<>();

        public PersonService() {
            people.add(new Person(1, "Sean Carroll"));
            people.add(new Person(2, "Carl Sagan"));
            people.add(new Person(3, "Richard Dawkins"));
            people.add(new Person(4, "Tim Maudlin"));
        }

        @Tool(description = "Get registered people")
        public List<Person> getPeople() {
            return people;
        }

        @Tool(description = "Get person by given id")
        public Optional<Person> getPersonById(int id) {
            return people
                    .stream()
                    .filter((Person person) -> person.id() == id)
                    .findFirst();
        }
    }

//    @RestController
//    @RequestMapping("/api")
//    public static class PersonController {
//        private final PersonService personService;
//
//        public PersonController(PersonService personService) {
//            this.personService = personService;
//        }
//
//        @GetMapping("/people")
//        public List<Person> getPeople() {
//            return personService.getPeople();
//        }
//
//        @GetMapping("/people/{id}")
//        public ResponseEntity<Person> getPersonById(@RequestParam int id) {
//            return ResponseEntity.of(personService.getPersonById(id));
//        }
//    }

    @Configuration
    public static class PeopleMCP {

        @Bean
        public ToolCallbackProvider peopleTools(PersonService personService) {
            return MethodToolCallbackProvider.builder().toolObjects(personService).build();
        }
    }
}
