package sgrv.be.core;

/** HTTP methods that can be stored in a runtime-visible {@link Route} annotation. */
public enum Method {
    OPTIONS,
    GET,
    HEAD,
    POST,
    PUT,
    PATCH,
    DELETE,
    TRACE,
    CONNECT,
    ANY
}
