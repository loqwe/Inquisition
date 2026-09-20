package moe.dazecake.inquisition.config;

import moe.dazecake.inquisition.filter.JwtTokenInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class JwtWebMvcConfig implements WebMvcConfigurer {
    private final JwtTokenInterceptor interceptor;

    public JwtWebMvcConfig(JwtTokenInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor);
    }
}
