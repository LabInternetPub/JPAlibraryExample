package cat.tecnocampus.fakegateway.webhook;

import cat.tecnocampus.fakegateway.config.GatewayProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Signs the exact bytes that go on the wire. The receiver has to verify against the raw body it
 * read, not against a re-serialised object, or the signature won't match — which is itself one
 * of the things UC-26 is there to teach.
 */
@Component
public class WebhookSigner {

    private static final String ALGORITHM = "HmacSHA256";

    private final GatewayProperties properties;

    public WebhookSigner(GatewayProperties properties) {
        this.properties = properties;
    }

    public String sign(String rawBody) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(properties.getWebhookSecret().getBytes(StandardCharsets.UTF_8), ALGORITHM));
            byte[] signature = mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8));
            return "sha256=" + HexFormat.of().formatHex(signature);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not sign the webhook body", e);
        }
    }
}
