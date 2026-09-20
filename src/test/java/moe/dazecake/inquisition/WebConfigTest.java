package moe.dazecake.inquisition;

import moe.dazecake.inquisition.config.WebConfig;
import moe.dazecake.inquisition.filter.JwtTokenInterceptor;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.web.servlet.config.annotation.CorsRegistration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebConfigTest {
    @Test
    void registersJwtInterceptorOnce() {
        JwtTokenInterceptor interceptor = mock(JwtTokenInterceptor.class);
        InterceptorRegistry registry = mock(InterceptorRegistry.class);
        InterceptorRegistration registration = mock(InterceptorRegistration.class, Answers.RETURNS_SELF);
        when(registry.addInterceptor(interceptor)).thenReturn(registration);

        new WebConfig(interceptor, "").addInterceptors(registry);

        verify(registry).addInterceptor(interceptor);
        verify(registration).addPathPatterns("/**");
    }

    @Test
    void registersOnlyConfiguredCorsOrigins() {
        CorsRegistry registry = mock(CorsRegistry.class);
        CorsRegistration registration = mock(CorsRegistration.class, Answers.RETURNS_SELF);
        when(registry.addMapping("/**")).thenReturn(registration);

        new WebConfig(mock(JwtTokenInterceptor.class),
                "https://panel.example, https://preview.example").addCorsMappings(registry);

        verify(registration).allowedOrigins("https://panel.example", "https://preview.example");
        verify(registration).allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS");
        verify(registration).allowedHeaders("Authorization", "Content-Type", "X-Device-Token");
        verify(registration).allowCredentials(true);
    }

    @Test
    void leavesCorsClosedWhenNoOriginIsConfigured() {
        CorsRegistry registry = mock(CorsRegistry.class);

        new WebConfig(mock(JwtTokenInterceptor.class), " ").addCorsMappings(registry);

        verify(registry, never()).addMapping("/**");
    }
}
