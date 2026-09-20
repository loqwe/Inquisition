package moe.dazecake.inquisition;

import moe.dazecake.inquisition.config.EndfieldCorsConfig;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.web.servlet.config.annotation.CorsRegistration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EndfieldCorsConfigTest {
    @Test
    void registersOnlyConfiguredOrigins() {
        CorsRegistry registry = mock(CorsRegistry.class);
        CorsRegistration registration = mock(CorsRegistration.class, Answers.RETURNS_SELF);
        when(registry.addMapping("/**")).thenReturn(registration);

        new EndfieldCorsConfig("https://panel.example, https://preview.example")
                .addCorsMappings(registry);

        verify(registration).allowedOrigins("https://panel.example", "https://preview.example");
        verify(registration).allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS");
        verify(registration).allowedHeaders("Authorization", "Content-Type", "X-Device-Token");
        verify(registration).allowCredentials(true);
    }

    @Test
    void leavesCorsClosedWhenNoOriginIsConfigured() {
        CorsRegistry registry = mock(CorsRegistry.class);

        new EndfieldCorsConfig("  ").addCorsMappings(registry);

        verify(registry, never()).addMapping("/**");
    }
}
