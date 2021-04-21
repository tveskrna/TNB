package org.jboss.fuse.tnb.mongodb.resource.openshift;

import org.jboss.fuse.tnb.common.config.OpenshiftConfiguration;
import org.jboss.fuse.tnb.common.config.SystemXConfiguration;
import org.jboss.fuse.tnb.common.deployment.OpenshiftNamedDeployable;
import org.jboss.fuse.tnb.common.openshift.OpenshiftClient;
import org.jboss.fuse.tnb.mongodb.service.MongoDB;

import org.junit.jupiter.api.extension.ExtensionContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.auto.service.AutoService;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

import cz.xtf.core.openshift.OpenShiftWaiters;
import cz.xtf.core.openshift.helpers.ResourceFunctions;
import io.fabric8.kubernetes.api.model.ContainerPort;
import io.fabric8.kubernetes.api.model.ContainerPortBuilder;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.ServicePortBuilder;
import io.fabric8.kubernetes.api.model.ServiceSpecBuilder;
import io.fabric8.kubernetes.client.PortForward;

@AutoService(MongoDB.class)
public class OpenshiftMongoDB extends MongoDB implements OpenshiftNamedDeployable {
    private static final Logger LOG = LoggerFactory.getLogger(OpenshiftMongoDB.class);

    private PortForward portForward;

    @Override
    public void create() {
        LOG.info("Deploying OpenShift MongoDB");
        List<ContainerPort> ports = new LinkedList<>();
        ports.add(new ContainerPortBuilder()
            .withName("mongodb")
            .withContainerPort(port())
            .withProtocol("TCP").build());
        // @formatter:off
        LOG.debug("Creating deploymentconfig {}", name());
        OpenshiftClient.get().deploymentConfigs().createOrReplaceWithNew()
            .editOrNewMetadata()
              .withName(name())
              .addToLabels(OpenshiftConfiguration.openshiftDeploymentLabel(), name())
            .endMetadata()
            .editOrNewSpec()
                .addToSelector(OpenshiftConfiguration.openshiftDeploymentLabel(), name())
                .withReplicas(1)
                .editOrNewTemplate()
                    .editOrNewMetadata()
                        .addToLabels(OpenshiftConfiguration.openshiftDeploymentLabel(), name())
                    .endMetadata()
                    .editOrNewSpec()
                        .addNewContainer()
                            .withName(name())
                            .withImage(SystemXConfiguration.mongoDbImage())
                            .addAllToPorts(ports)
                            .addAllToEnv(containerEnvironment().entrySet().stream().map(e -> new EnvVar(e.getKey(), e.getValue(), null)).collect(Collectors.toList()))
                        .endContainer()
                    .endSpec()
                .endTemplate()
                .addNewTrigger()
                    .withType("ConfigChange")
                .endTrigger()
            .endSpec()
            .done();

        ServiceSpecBuilder serviceSpecBuilder = new ServiceSpecBuilder().addToSelector(OpenshiftConfiguration.openshiftDeploymentLabel(), name());

        serviceSpecBuilder.addToPorts(new ServicePortBuilder()
            .withName(name())
            .withPort(port())
            .withTargetPort(new IntOrString(port()))
            .build());

        LOG.debug("Creating service {}", name());
        OpenshiftClient.get().services().createOrReplaceWithNew()
            .editOrNewMetadata()
                .withName(name())
                .addToLabels(OpenshiftConfiguration.openshiftDeploymentLabel(), name())
            .endMetadata()
            .editOrNewSpecLike(serviceSpecBuilder.build())
            .endSpec()
            .done();
        // @formatter:on
    }

    @Override
    public void undeploy() {
        LOG.info("Undeploying OpenShift MongoDB");
        client().close();
        try {
            LOG.debug("Closing port-forward");
            portForward.close();
        } catch (IOException ignored) {
        }

        LOG.debug("Deleting service {}", name());
        OpenshiftClient.get().services().withName(name()).delete();
        LOG.debug("Deleting deploymentconfig {}", name());
        OpenshiftClient.get().deploymentConfigs().withName(name()).delete();
        OpenShiftWaiters.get(OpenshiftClient.get(), () -> false).areExactlyNPodsReady(0, OpenshiftConfiguration.openshiftDeploymentLabel(), name())
            .timeout(120_000).waitFor();
    }

    @Override
    public boolean isReady() {
        return ResourceFunctions.areExactlyNPodsReady(1)
            .apply(OpenshiftClient.get().getLabeledPods(OpenshiftConfiguration.openshiftDeploymentLabel(), name()))
            && OpenshiftClient.get().getPodLog(name()).contains("Transition to primary complete; database writes are now permitted");
    }

    @Override
    public boolean isDeployed() {
        return OpenshiftClient.get().getLabeledPods(OpenshiftConfiguration.openshiftDeploymentLabel(), name()).size() != 0;
    }

    @Override
    public String name() {
        return "mongodb";
    }

    @Override
    public MongoClient client() {
        if (client == null) {
            LOG.debug("Creating port-forward to {} for port {}", name(), port());
            portForward = OpenshiftClient.get().services().withName(name()).portForward(port(), port());
            LOG.debug("Creating new MongoClient instance");
            client = MongoClients.create(replicaSetUrl().replace("@" + name(), "@localhost"));
        }
        return client;
    }

    @Override
    public String replicaSetUrl() {
        return String.format("mongodb://%s:%s@%s:%d/%s", account().username(), account().password(), name(), port(), account().database());
    }

    @Override
    public void afterAll(ExtensionContext extensionContext) throws Exception {
        undeploy();
    }

    @Override
    public void beforeAll(ExtensionContext extensionContext) throws Exception {
        deploy();
    }
}