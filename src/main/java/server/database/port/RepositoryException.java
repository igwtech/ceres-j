package server.database.port;

/**
 * Unchecked persistence failure raised by repository adapters.
 *
 * <p>The repository <i>ports</i> deliberately do not expose
 * {@link java.sql.SQLException} (a JDBC/adapter concern). Adapters wrap any
 * backend-specific failure in this exception so the importer application
 * layer can catch a single, technology-neutral type and decide whether to
 * log-and-continue (the importers' policy: never abort server startup).
 */
public class RepositoryException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public RepositoryException(String message, Throwable cause) {
        super(message, cause);
    }

    public RepositoryException(String message) {
        super(message);
    }
}
