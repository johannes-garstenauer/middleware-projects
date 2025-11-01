package mw.util;

import javax.ws.rs.client.*;
import javax.ws.rs.ext.Provider;
import java.io.IOException;

@Provider
public class ClientTimingFilter implements ClientRequestFilter, ClientResponseFilter {
    private static final String START_TIME = "mw.client.start";

    @Override
    public void filter(ClientRequestContext requestContext) throws IOException {
        requestContext.setProperty(START_TIME, System.nanoTime());
    }

    @Override
    public void filter(ClientRequestContext requestContext, ClientResponseContext responseContext)
            throws IOException {
        Object start = requestContext.getProperty(START_TIME);
        if (start instanceof Long) {
            long durationNs = System.nanoTime() - (Long) start;
            double durationMs = durationNs / 1_000_000.0;
            System.out.printf("[CLIENT] %s %s took %.2f ms%n",
                    requestContext.getMethod(),
                    requestContext.getUri(),
                    durationMs);
        }
    }
}
