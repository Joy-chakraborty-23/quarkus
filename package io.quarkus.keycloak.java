package io.quarkus.keycloak.devservices;

import java.util.Map;
import java.util.Optional;

import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.ClientsResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;

import io.quarkus.deployment.IsNormal;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.DevServicesResultBuildItem;
import io.quarkus.deployment.builditem.DevServicesResultBuildItem.RunningDevService;
import io.quarkus.keycloak.admin.client.common.KeycloakAdminClientConfig;
import io.quarkus.keycloak.devservices.KeycloakDevServicesConfig.DevServicesConfig;

public class KeycloakDevServicesProcessor {

    @BuildStep(onlyIfNot = IsNormal.class)
    public void configureClientRoles(
            Optional<RunningDevService> devService,
            KeycloakDevServicesConfig config,
            KeycloakAdminClientConfig adminClientConfig,
            KeycloakDevServicesClientRolesConfig clientRolesConfig) {

        if (devService.isEmpty() || !config.devservices.enabled) {
            return;
        }

        if (clientRolesConfig.clientRoles.isEmpty() || clientRolesConfig.clientRoles.get().isEmpty()) {
            return;
        }

        DevServicesConfig devservices = config.devservices;
        String serverUrl = "http://localhost:" + devService.get().getConfig().get("quarkus.keycloak.devservices.port");

        try (Keycloak keycloakAdmin = Keycloak.getInstance(
                serverUrl,
                devservices.realmName,
                adminClientConfig.username.get(),
                adminClientConfig.password.get(),
                adminClientConfig.clientId.get())) {

            // Get the client
            ClientsResource clients = keycloakAdmin.realm(devservices.realmName).clients();
            ClientRepresentation client = clients.findByClientId(devservices.clientId).get(0);

            // Add roles to the client
            for (String roleName : clientRolesConfig.clientRoles.get()) {
                RoleRepresentation role = new RoleRepresentation();
                role.setName(roleName);
                role.setClientRole(true);
                clients.get(client.getId()).roles().create(role);
            }
        }
    }
}