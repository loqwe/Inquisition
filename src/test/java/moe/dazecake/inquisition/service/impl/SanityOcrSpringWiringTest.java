package moe.dazecake.inquisition.service.impl;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.context.support.TestPropertySourceUtils;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

class SanityOcrSpringWiringTest {

    @Test
    void productionConstructorsCanBeWiredBySpring() {
        try (var context = new AnnotationConfigApplicationContext()) {
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                    "inquisition.ocr.enabled=false",
                    "inquisition.ocr.service-url=http://sanity-ocr:8000/v1/sanity",
                    "inquisition.ocr.allowed-image-hosts=inquisition-img.example");
            context.getBeanFactory().registerSingleton(
                    "accountRuntimeService", mock(AccountRuntimeService.class));
            context.register(SanityOcrClient.class, SanityOcrService.class);

            context.refresh();

            assertNotNull(context.getBean(SanityOcrClient.class));
            assertNotNull(context.getBean(SanityOcrService.class));
        }
    }
}
