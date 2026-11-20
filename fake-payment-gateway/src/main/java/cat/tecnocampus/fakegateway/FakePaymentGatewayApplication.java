package cat.tecnocampus.fakegateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Stands in for the payment provider. It is deliberately a separate application: the teams
 * integrate with it over HTTP only, the same way they would with a real provider, and never
 * read or import its code.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class FakePaymentGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(FakePaymentGatewayApplication.class, args);
    }
}
