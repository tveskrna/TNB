package org.jboss.fuse.tnb.common.openshift;

import org.jboss.fuse.tnb.common.config.OpenshiftConfiguration;
import org.jboss.fuse.tnb.common.utils.WaitUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Map;
import java.util.function.BooleanSupplier;

import cz.xtf.core.openshift.OpenShift;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.openshift.api.model.operatorhub.v1alpha1.InstallPlan;
import io.fabric8.openshift.api.model.operatorhub.v1alpha1.Subscription;

public class OpenshiftClient {
    private static final Logger LOG = LoggerFactory.getLogger(OpenshiftClient.class);
    private static OpenShift client;

    static {
        //init client
        LOG.debug("Creating new OpenShift client");
        client = OpenShift.get(
                OpenshiftConfiguration.openshiftUrl(),
                OpenshiftConfiguration.openshiftNamespace(),
                OpenshiftConfiguration.openshiftUsername(),
                OpenshiftConfiguration.openshiftPassword()
        );
    }

    public static OpenShift get() {
        return client;
    }

    /**
     * Creates the operatorgroup and subscription.
     * @param channel operatorhub channel
     * @param operatorName operator name
     * @param source operator catalog source
     * @param subscriptionName name of the subscription
     */
    public static void createSubscription(String channel, String operatorName, String source, String subscriptionName) {
        LOG.info("Creating subcription with name {}, for operator {}, channel {}, source {}", subscriptionName, operatorName, channel, source);
        LOG.debug("Creating operator group {}", subscriptionName);
        if (get().operatorHub().operatorGroups().inNamespace(OpenshiftConfiguration.openshiftNamespace()).
            list().getItems().size() == 0) {
            get().operatorHub().operatorGroups().createOrReplaceWithNew()
                .withNewMetadata()
                    .withName(subscriptionName)
                .endMetadata()
                .withNewSpec()
                    .withTargetNamespaces(OpenshiftConfiguration.openshiftNamespace())
                .endSpec()
                .done();
        }

        get().operatorHub().subscriptions().createOrReplaceWithNew()
            .editOrNewMetadata()
                .withName(subscriptionName)
            .endMetadata()
            .withNewSpec()
                .withName(operatorName)
                .withChannel(channel)
                .withSource(source)
                .withSourceNamespace("openshift-marketplace")
            .endSpec()
            .done();
    }

    /**
     * Waits until the install plan for a given subscription completes.
     *
     * @param name subscription name
     */
    public static void waitForCompletion(String name) {
        WaitUtils.waitFor(() -> {
            Subscription subscription = get().operatorHub().subscriptions().withName(name).get();
            if (subscription == null || subscription.getStatus() == null || subscription.getStatus().getInstallplan() == null) {
                return false;
            }
            String ipName = subscription.getStatus().getInstallplan().getName();
            InstallPlan installPlan = get().operatorHub().installPlans().withName(ipName).get();
            if (installPlan == null || installPlan.getStatus() == null || installPlan.getStatus().getPhase() == null) {
                return false;
            }
            return installPlan.getStatus().getPhase().equalsIgnoreCase("complete");
        }, 60, 5000L, String.format("Waiting until the install plan from subscription %s is complete", name));
    }

    /**
     * Deletes the operatorgroup subscription.
     * @param name subscription name
     */
    public static void deleteSubscription(String name) {
        LOG.info("Deleting subscription {}", name);
        Subscription subscription = get().operatorHub().subscriptions().withName(name).get();
        String csvName = subscription.getStatus().getCurrentCSV();
        //CSV being null can happen if you delete the subscription without deleting the CSV, then your new subscription is CSV-less
        if (csvName != null) {
            get().operatorHub().clusterServiceVersions().withName(csvName).delete();
        }
        get().operatorHub().subscriptions().withName(name).delete();
    }

    /**
     * Waits until the image stream is populated with given tag.
     * @param name imagestream name
     * @param tag imagestream tag
     */
    public static void waitForImageStream(String name, String tag) {
        WaitUtils.waitFor(() -> OpenshiftClient.get().imageStreams().withName(name).get().getSpec().getTags().stream()
            .anyMatch(t -> tag.equals(t.getName())), 24, 5000L, String.format("Waiting until the imagestream %s contains %s tag", name, tag));
    }

    /**
     * Starts a new s2i build from a given file.
     * @param name buildconfig name
     * @param filePath path to the file
     */
    public static void doS2iBuild(String name, Path filePath) {
        LOG.info("Instantiating a new build for buildconfig {} from file {}", name, filePath.toAbsolutePath());
        OpenshiftClient.get().buildConfigs().withName(name).instantiateBinary().fromFile(filePath.toFile());

        BooleanSupplier success = () -> "complete".equalsIgnoreCase(OpenshiftClient.get()
            .getBuild(name + "-" + OpenshiftClient.get().getBuildConfig(name).getStatus().getLastVersion()).getStatus().getPhase());
        BooleanSupplier fail = () -> "failed".equalsIgnoreCase(OpenshiftClient.get()
            .getBuild(name + "-" + OpenshiftClient.get().getBuildConfig(name).getStatus().getLastVersion()).getStatus().getPhase());

        LOG.info("Waiting until the build completes");
        WaitUtils.waitFor(success, fail, 5000L, "Waiting until the build completes");
    }

    /**
     * Create namespace (name obtained from system property openshift.namespace)
     */
    public static void createNamespace(){
        createNamespace(OpenshiftConfiguration.openshiftNamespace());
    }

    /**
     * Create namespace with given name
     * @param name of namespace to be created
     */
    public static void createNamespace(String name){
        if ((name == null) || (name.isEmpty())) {
            LOG.info("Skipped creating namespace, name null or empty");
            return;
        }
        Namespace ns = new NamespaceBuilder().withNewMetadata().withName(name).endMetadata().build();
        if (client.namespaces().withName(name).get() == null) {
            client.namespaces().create(ns);
            LOG.info("Created namespace " + name);
        } else {
            LOG.info("Skipped creating namespace " + name + ", already exists");
        }
    }

    /**
     * Delete namespace (name obtained from system property openshift.namespace)
     */
    public static void deleteNamespace(){
        deleteNamespace(OpenshiftConfiguration.openshiftNamespace());
    }

    /**
     * Delete namespace of given name
     * @param name of namespace to be deleted
     */
    public static void deleteNamespace(String name){
        if ((name == null) || (name.isEmpty())) {
            LOG.info("Skipped deleting namespace, name null or empty");
            return;
        }
        if (client.namespaces().withName(name).get() == null) {
            LOG.info("Skipped deleting namespace " + name + ", not found");
        } else {
            client.namespaces().withName(name).delete();
            LOG.info("Deleted namespace " + name);
        }
    }

    /**
     * Creates a config map with given name and data.
     * @param name configmap name
     * @param data map with data
     */
    public static void createConfigMap(String name, Map<String, String> data) {
        client.configMaps().withName(name).createOrReplaceWithNew()
            .withNewMetadata()
                .withName(name)
            .endMetadata()
            .withData(data)
            .done();
    }
}