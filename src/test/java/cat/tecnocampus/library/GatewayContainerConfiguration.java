package cat.tecnocampus.library;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;

import java.nio.file.Path;

/**
 * The real fake gateway, in a container built from {@code fake-payment-gateway/} (the image is
 * cached by Docker after the first build). Tests talk to it over HTTP, exactly as the application
 * does: an in-memory double would never show a timeout, a late answer or a 503.
 */
@TestConfiguration(proxyBeanMethods = false)
public class GatewayContainerConfiguration {

    private static final int PORT = 9090;

    @Bean(initMethod = "start", destroyMethod = "stop")
    GenericContainer<?> paymentGatewayContainer() {
        ImageFromDockerfile image = new ImageFromDockerfile("library-fake-gateway-test", false)
                .withFileFromPath(".", Path.of("fake-payment-gateway"));
        return new GenericContainer<>(image)
                .withExposedPorts(PORT)
                .waitingFor(Wait.forHttp("/admin/config").forPort(PORT));
    }

    @Bean
    DynamicPropertyRegistrar paymentGatewayUrl(@Qualifier("paymentGatewayContainer") GenericContainer<?> gateway) {
        return registry -> registry.add("library.payment.url",
                () -> "http://" + gateway.getHost() + ":" + gateway.getMappedPort(PORT));
    }
}
