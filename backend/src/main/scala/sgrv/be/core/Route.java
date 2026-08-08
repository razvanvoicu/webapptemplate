package sgrv.be.core;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Declares the HTTP methods and path served by an independently loadable route object. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Route {
    Method[] methods();
    String path();

    /**
     * When {@code true} (the default), route discovery rejects a request with no valid browser session before the
     * route's handler runs, so the handler need not check authentication itself. Set to {@code false} for routes
     * that must remain reachable without a session, such as the login flow itself.
     */
    boolean auth() default true;

    /**
     * When {@code true} (default {@code false}), route discovery additionally requires a {@code ?pwd=} query
     * parameter matching the {@code ADMIN_PASSWORD} environment variable, an alternative to a Google session for
     * reaching a diagnostic or admin route directly by URL. Independent of {@code auth}: combine with
     * {@code auth = false} to make a route reachable by password alone.
     */
    boolean adminPwd() default false;
}
