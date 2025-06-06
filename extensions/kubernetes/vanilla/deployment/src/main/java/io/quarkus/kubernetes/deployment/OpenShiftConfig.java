package io.quarkus.kubernetes.deployment;

import static io.quarkus.kubernetes.deployment.Constants.OPENSHIFT;
import static io.quarkus.kubernetes.deployment.Constants.S2I;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import io.quarkus.container.image.deployment.ContainerImageCapabilitiesUtil;
import io.quarkus.container.image.deployment.ContainerImageConfig;
import io.quarkus.deployment.Capabilities;
import io.quarkus.deployment.Capability;
import io.quarkus.kubernetes.spi.DeployStrategy;
import io.quarkus.runtime.annotations.ConfigDocMapKey;
import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * OpenShift
 */
@ConfigMapping(prefix = "quarkus.openshift")
@ConfigRoot(phase = ConfigPhase.BUILD_TIME)
public interface OpenShiftConfig extends PlatformConfiguration {

    @Override
    default String targetPlatformName() {
        return Constants.OPENSHIFT;
    }

    enum OpenshiftFlavor {
        v3,
        v4;
    }

    /**
     * The OpenShift flavor / version to use.
     * Older versions of OpenShift have minor differences in the labels and fields they support.
     * This option allows users to have their manifests automatically aligned to the OpenShift 'flavor' they use.
     */
    @WithDefault("v4")
    OpenshiftFlavor flavor();

    /**
     * The kind of the deployment resource to use.
     * Supported values are 'Deployment', 'StatefulSet', 'Job', 'CronJob' and 'DeploymentConfig'. Defaults to 'DeploymentConfig'
     * if {@code flavor == v3}, or 'Deployment' otherwise.
     * DeploymentConfig is deprecated as of OpenShift 4.14. See https://access.redhat.com/articles/7041372 for details.
     */
    Optional<DeploymentResourceKind> deploymentKind();

    /**
     * The number of desired pods
     */
    @WithDefault("1")
    Integer replicas();

    /**
     * The nodePort to set when serviceType is set to nodePort
     */
    OptionalInt nodePort();

    /**
     * Sidecar containers
     *
     * @deprecated Use the {@code sidecars} property instead
     */
    @Deprecated
    Map<String, ContainerConfig> containers();

    /**
     * OpenShift route configuration
     */
    RouteConfig route();

    /**
     * Job configuration. It's only used if and only if {@code quarkus.openshift.deployment-kind} is `Job`.
     */
    JobConfig job();

    /**
     * CronJob configuration. It's only used if and only if {@code quarkus.openshift.deployment-kind} is `CronJob`.
     */
    CronJobConfig cronJob();

    /**
     * Debug configuration to be set in pods.
     */
    DebugConfig remoteDebug();

    /**
     * Flag to enable init task externalization.
     * When enabled (default), all initialization tasks
     * created by extensions, will be externalized as Jobs.
     * In addition, the deployment will wait for these jobs.
     *
     * @deprecated use {@link #initTasks} configuration instead
     */
    @Deprecated(since = "3.1", forRemoval = true)
    @WithDefault("true")
    boolean externalizeInit();

    /**
     * Init tasks configuration.
     * <p>
     * The init tasks are automatically generated by extensions like Flyway to perform the database migration before starting
     * up the application.
     * <p>
     * This property is only taken into account if `quarkus.openshift.externalize-init` is true.
     */
    @ConfigDocMapKey("task-name")
    Map<String, InitTaskConfig> initTasks();

    /**
     * Default init tasks configuration.
     * <p>
     * The init tasks are automatically generated by extensions like Flyway to perform the database migration before staring
     * up the application.
     */
    InitTaskConfig initTaskDefaults();

    /**
     * If set to true, Quarkus will attempt to deploy the application to the target Kubernetes cluster
     */
    @WithDefault("false")
    boolean deploy();

    /**
     * If deploy is enabled, it will follow this strategy to update the resources to the target Kubernetes cluster.
     */
    @WithDefault("CreateOrUpdate")
    DeployStrategy deployStrategy();

    static boolean isOpenshiftBuildEnabled(ContainerImageConfig containerImageConfig, Capabilities capabilities) {
        boolean implicitlyEnabled = ContainerImageCapabilitiesUtil.getActiveContainerImageCapability(capabilities)
                .filter(c -> c.contains(OPENSHIFT) || c.contains(S2I)).isPresent();
        return containerImageConfig.builder().map(b -> b.equals(OPENSHIFT) || b.equals(S2I)).orElse(implicitlyEnabled);
    }

    default DeploymentResourceKind getDeploymentResourceKind(Capabilities capabilities) {
        if (deploymentKind().isPresent()) {
            return deploymentKind().filter(k -> k.isAvailalbleOn(OPENSHIFT)).get();
        } else if (capabilities.isPresent(Capability.PICOCLI)) {
            return DeploymentResourceKind.Job;
        }
        return (flavor() == OpenshiftFlavor.v3) ? DeploymentResourceKind.DeploymentConfig : DeploymentResourceKind.Deployment;
    }

    @Deprecated
    default Map<String, ContainerConfig> getSidecars() {
        if (!containerName().isEmpty() && !sidecars().isEmpty()) {
            // done in order to make migration to the new property straight-forward
            throw new IllegalStateException(
                    "'quarkus.openshift.sidecars' and 'quarkus.openshift.containers' cannot be used together. Please use the former as the latter has been deprecated");
        }
        if (!containers().isEmpty()) {
            return containers();
        }
        return sidecars();
    }
}
