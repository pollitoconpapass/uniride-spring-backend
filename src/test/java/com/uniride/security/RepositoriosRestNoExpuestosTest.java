package com.uniride.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Proteccion de cierre: no publicar los JpaRepository como recursos REST.
 * Los usuarios deben acceder exclusivamente a los controladores propios,
 * donde se aplican autorizacion, validacion y reglas de negocio.
 */
@SpringBootTest
class RepositoriosRestNoExpuestosTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void noSeExponenRepositoriosJpaComoEndpointsRestAutomaticos() {
        List<String> mappingsDeRepositorios = context
                .getBeansOfType(HandlerMapping.class)
                .values().stream()
                .map(handlerMapping -> handlerMapping.getClass().getName())
                .filter(nombre -> nombre.startsWith("org.springframework.data.rest.webmvc")
                        || nombre.contains("RepositoryRestHandlerMapping"))
                .toList();

        assertThat(mappingsDeRepositorios)
                .as("Los JpaRepository no deben tener endpoints REST automaticos")
                .isEmpty();
    }
}
