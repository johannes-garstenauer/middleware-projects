package mw.namenode;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.Provider;

@Provider
public class MWErrorHandler implements ExceptionMapper<Throwable> {
    public Response toResponse(Throwable error) {
        // Ausgabe der Exception
        error.printStackTrace();
        // Propagieren der Exception
        if(error instanceof WebApplicationException) {
            return ((WebApplicationException) error).getResponse();
        }else {
            return Response.serverError().build();
        }
    }
}
