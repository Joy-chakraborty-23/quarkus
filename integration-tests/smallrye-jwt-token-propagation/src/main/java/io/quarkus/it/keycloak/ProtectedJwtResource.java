package io.quarkus.it.keycloak;

import java.security.Principal;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.SecurityContext;

import org.eclipse.microprofile.jwt.JsonWebToken;

import io.quarkus.security.Authenticated;
import io.quarkus.security.PermissionsAllowed;
import io.quarkus.security.identity.SecurityIdentity;

@Path("/web-app")
@Authenticated
public class ProtectedJwtResource {

    @Inject
    SecurityIdentity identity;

    @Inject
    Principal principal;

    @Inject
    JsonWebToken accessToken;

    @Context
    SecurityContext securityContext;

    @GET
    @Path("test-security")
    @RolesAllowed("viewer")
    public String testSecurity() {
        return securityContext.getUserPrincipal().getName() + ":" + identity.getPrincipal().getName() + ":"
                + principal.getName();
    }

    @GET
    @Path("test-security-with-augmentors")
    @PermissionsAllowed(permission = CustomPermission.class, value = "augmented")
    public String testSecurityWithAugmentors() {
        return securityContext.getUserPrincipal().getName() + ":" + identity.getPrincipal().getName() + ":"
                + principal.getName();
    }

    @POST
    @Path("test-security")
    @Consumes("application/json")
    @RolesAllowed("viewer")
    public String testSecurityJson(User user) {
        return user.getName() + ":" + securityContext.getUserPrincipal().getName();
    }

    @GET
    @Path("test-security-jwt")
    @RolesAllowed("viewer")
    public String testSecurityJwt() {
        return accessToken.getName() + ":" + identity.getPrincipal().getName() + ":" + principal.getName()
                + ":" + accessToken.getGroups().iterator().next() + ":" + accessToken.getClaim("email");
    }
}
