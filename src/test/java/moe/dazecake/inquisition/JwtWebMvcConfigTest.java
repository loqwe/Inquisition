package moe.dazecake.inquisition;

import moe.dazecake.inquisition.config.JwtWebMvcConfig;
import moe.dazecake.inquisition.filter.JwtTokenInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class JwtWebMvcConfigTest {
    @Test
    void registersJwtInterceptorForMvcRequests() {
        JwtTokenInterceptor interceptor = mock(JwtTokenInterceptor.class);
        InterceptorRegistry registry = mock(InterceptorRegistry.class);

        new JwtWebMvcConfig(interceptor).addInterceptors(registry);

        verify(registry).addInterceptor(interceptor);
    }
}
