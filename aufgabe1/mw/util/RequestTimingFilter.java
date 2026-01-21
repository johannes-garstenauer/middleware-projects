package mw.util;

import javax.ws.rs.container.*;
import javax.ws.rs.ext.Provider;
import java.io.IOException;

@Provider
public class RequestTimingFilter implements ContainerRequestFilter, ContainerResponseFilter {
    private static final String START_TIME = "mw.start.time";

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        requestContext.setProperty(START_TIME, System.nanoTime());
    }

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext)
            throws IOException {
        Object start = requestContext.getProperty(START_TIME);
        if (start instanceof Long) {
            long durationNs = System.nanoTime() - (Long) start;
            double durationMs = durationNs / 1_000_000.0;
            System.out.printf("[SERVER] %s %s took %.2f ms%n",
                    requestContext.getMethod(),
                    requestContext.getUriInfo().getRequestUri(),
                    durationMs);
        }
    }
}