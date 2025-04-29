package io.quarkus.keycloak.devservices;

import java.util.List;
import java.util.Optional;

import io.quarkus.runtime.annotations.ConfigGroup;
import io.quarkus.runtime.annotations.ConfigItem;

@ConfigGroup
public class KeycloakDevServicesClientRolesConfig {

    //List of roles to be assigned to the client created by Keycloak DevServices
     
    @ConfigItem
    public Optional<List<String>> clientRoles;
}