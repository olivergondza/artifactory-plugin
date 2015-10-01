/*
 * Copyright (C) 2010 JFrog Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jfrog.hudson;

import com.google.common.collect.Lists;
import hudson.model.BuildListener;
import hudson.util.XStream2;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;
import org.jfrog.build.api.util.NullLog;
import org.jfrog.build.client.ArtifactoryHttpClient;
import org.jfrog.build.client.ArtifactoryVersion;
import org.jfrog.build.client.ProxyConfiguration;
import org.jfrog.build.extractor.clientConfiguration.client.ArtifactoryBuildInfoClient;
import org.jfrog.build.extractor.clientConfiguration.client.ArtifactoryDependenciesClient;
import org.jfrog.hudson.util.CredentialManager;
import org.jfrog.hudson.util.Credentials;
import org.jfrog.hudson.util.JenkinsBuildInfoLog;
import org.jfrog.hudson.util.RepositoriesUtils;
import org.jfrog.hudson.util.converters.ArtifactoryServerConverter;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.IOException;
import java.io.Serializable;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Represents an artifactory instance.
 *
 * @author Yossi Shaul
 */
public class ArtifactoryServer implements Serializable {
    private static final Logger log = Logger.getLogger(ArtifactoryServer.class.getName());

    private static final int DEFAULT_CONNECTION_TIMEOUT = 300;    // 5 Minutes
    private final String url;
    private final String id;
    // Network timeout in seconds to use both for connection establishment and for unanswered requests
    private int timeout = DEFAULT_CONNECTION_TIMEOUT;
    private boolean bypassProxy;

    /**
     * List of repository keys, last time we checked. Copy on write semantics.
     */
    private transient volatile List<String> repositories;

    private transient volatile List<VirtualRepository> virtualRepositories;
    /**
     * @deprecated: Use org.jfrog.hudson.DeployerOverrider#getOverridingDeployerCredentials()
     */
    @Deprecated
    private transient String userName;
    /**
     * @deprecated: Use org.jfrog.hudson.DeployerOverrider#getOverridingDeployerCredentials()
     */
    @Deprecated
    private transient String password;    // base64 scrambled password
    /**
     * @deprecated: Use org.jfrog.hudson.ArtifactoryServer#getDeployerCredentials()()
     */
    @Deprecated
    private Credentials deployerCredentials;
    /**
     * @deprecated: Use org.jfrog.hudson.ArtifactoryServer#getResolverCredentials()
     */
    @Deprecated
    private Credentials resolverCredentials;

    private CredentialsConfig deployerCredentialsConfig;

    private CredentialsConfig resolverCredentialsConfig;

    @DataBoundConstructor
    public ArtifactoryServer(String serverId, String artifactoryUrl, CredentialsConfig deployerCredentialsConfig,
                             CredentialsConfig resolverCredentialsConfig, int timeout, boolean bypassProxy) {
        this.url = StringUtils.removeEnd(artifactoryUrl, "/");
        this.deployerCredentialsConfig = deployerCredentialsConfig;
        this.resolverCredentialsConfig = resolverCredentialsConfig;
        this.timeout = timeout > 0 ? timeout : DEFAULT_CONNECTION_TIMEOUT;
        this.bypassProxy = bypassProxy;
        this.id = serverId == null || serverId.isEmpty() ? artifactoryUrl.hashCode() + "@" + System.currentTimeMillis() : serverId;
    }

    public String getName() {
        return id;
    }

    public String getUrl() {
        return url != null ? url : getName();
    }

    public CredentialsConfig getDeployerCredentialsConfig() {
        return deployerCredentialsConfig;
    }

    public CredentialsConfig getResolverCredentialsConfig() {
        return resolverCredentialsConfig;
    }

    public int getTimeout() {
        return timeout;
    }

    public boolean isBypassProxy() {
        return bypassProxy;
    }

    public List<String> getLocalRepositoryKeys(Credentials credentials) {
        ArtifactoryBuildInfoClient client = createArtifactoryClient(credentials.getUsername(),
                credentials.getPassword(), createProxyConfiguration(Jenkins.getInstance().proxy));
        try {
            repositories = client.getLocalRepositoriesKeys();
        } catch (IOException e) {
            if (log.isLoggable(Level.FINE)) {
                log.log(Level.WARNING, "Could not obtain local repositories list from '" + url + "'", e);
            } else {
                log.log(Level.WARNING,
                        "Could not obtain local repositories list from '" + url + "': " + e.getMessage());
            }
            return Lists.newArrayList();
        } finally {
            client.shutdown();
        }
        return repositories;
    }

    public List<String> getReleaseRepositoryKeysFirst(DeployerOverrider deployerOverrider) {
        CredentialsConfig credentialsConfig = CredentialManager.getPreferredDeployer(deployerOverrider, this);
        List<String> repositoryKeys = getLocalRepositoryKeys(credentialsConfig.getCredentials());
        if (repositoryKeys == null || repositoryKeys.isEmpty()) {
            return Lists.newArrayList();
        }
        Collections.sort(repositoryKeys, new RepositoryComparator());
        return repositoryKeys;
    }

    public List<String> getSnapshotRepositoryKeysFirst(DeployerOverrider deployerOverrider) {
        CredentialsConfig credentialsConfig = CredentialManager.getPreferredDeployer(deployerOverrider, this);
        List<String> repositoryKeys = getLocalRepositoryKeys(credentialsConfig.getCredentials());
        if (repositoryKeys == null || repositoryKeys.isEmpty()) {
            return Lists.newArrayList();
        }
        Collections.sort(repositoryKeys, Collections.reverseOrder(new RepositoryComparator()));
        return repositoryKeys;
    }

    public Map getStagingStrategy(PluginSettings selectedStagingPlugin, String buildName) throws IOException {
        CredentialsConfig resolvingCredentialsConfig = getResolvingCredentialsConfig();
        ArtifactoryBuildInfoClient client = createArtifactoryClient(resolvingCredentialsConfig.provideUsername(),
                resolvingCredentialsConfig.getPassword(), createProxyConfiguration(Jenkins.getInstance().proxy));
        try {
            return client.getStagingStrategy(selectedStagingPlugin.getPluginName(), buildName,
                    selectedStagingPlugin.getParamMap());
        } finally {
            client.shutdown();
        }
    }

    public List<VirtualRepository> getVirtualRepositoryKeys(ResolverOverrider resolverOverrider) {
        CredentialsConfig preferredResolver = CredentialManager.getPreferredResolver(resolverOverrider, this);
        ArtifactoryBuildInfoClient client = createArtifactoryClient(preferredResolver.provideUsername(),
                preferredResolver.providePassword(), createProxyConfiguration(Jenkins.getInstance().proxy));
        try {
            virtualRepositories = RepositoriesUtils.generateVirtualRepos(client);
        } catch (IOException e) {
            if (log.isLoggable(Level.FINE)) {
                log.log(Level.WARNING, "Could not obtain virtual repositories list from '" + url + "'", e);
            } else {
                log.log(Level.WARNING,
                        "Could not obtain virtual repositories list from '" + url + "': " + e.getMessage());
            }
            return Lists.newArrayList();
        } finally {
            client.shutdown();
        }

        return virtualRepositories;
    }

    public boolean isArtifactoryPro(DeployerOverrider deployerOverrider) {
        CredentialsConfig credentialsConfig = CredentialManager.getPreferredDeployer(deployerOverrider, this);
        try {
            ArtifactoryHttpClient client = new ArtifactoryHttpClient(url, credentialsConfig.provideUsername(),
                    credentialsConfig.providePassword(), new NullLog());
            ArtifactoryVersion version = client.getVersion();
            return version.hasAddons();
        } catch (IOException e) {
            if (log.isLoggable(Level.FINE)) {
                log.log(Level.WARNING, "Could not obtain artifactory version from '" + url + "'", e);
            } else {
                log.log(Level.WARNING,
                        "Could not obtain artifactory version from '" + url + "': " + e.getMessage());
            }
        }
        return false;
    }

    public List<UserPluginInfo> getStagingUserPluginInfo(DeployerOverrider deployerOverrider) {
        List<UserPluginInfo> infosToReturn = Lists.newArrayList(UserPluginInfo.NO_PLUGIN);
        gatherUserPluginInfo(infosToReturn, "staging", deployerOverrider);
        return infosToReturn;
    }

    public List<UserPluginInfo> getPromotionsUserPluginInfo(DeployerOverrider deployerOverrider) {
        List<UserPluginInfo> infosToReturn = Lists.newArrayList(UserPluginInfo.NO_PLUGIN);
        gatherUserPluginInfo(infosToReturn, "promotions", deployerOverrider);
        return infosToReturn;
    }

    /**
     * This method might run on slaves, this is why we provide it with a proxy from the master config
     */
    public ArtifactoryBuildInfoClient createArtifactoryClient(String userName, String password,
                                                              ProxyConfiguration proxyConfiguration) {
        ArtifactoryBuildInfoClient client = new ArtifactoryBuildInfoClient(url, userName, password, new NullLog());
        client.setConnectionTimeout(timeout);
        if (!bypassProxy && proxyConfiguration != null) {
            client.setProxyConfiguration(proxyConfiguration.host,
                    proxyConfiguration.port,
                    proxyConfiguration.username,
                    proxyConfiguration.password);
        }

        return client;
    }

    public ProxyConfiguration createProxyConfiguration(hudson.ProxyConfiguration proxy) {
        ProxyConfiguration proxyConfiguration = null;
        if (proxy != null) {
            proxyConfiguration = new ProxyConfiguration();
            proxyConfiguration.host = proxy.name;
            proxyConfiguration.port = proxy.port;
            proxyConfiguration.username = proxy.getUserName();
            proxyConfiguration.password = proxy.getPassword();
        }

        return proxyConfiguration;
    }

    /**
     * This method might run on slaves, this is why we provide it with a proxy from the master config
     */
    public ArtifactoryDependenciesClient createArtifactoryDependenciesClient(String userName, String password,
                                                                             ProxyConfiguration proxyConfiguration, BuildListener listener) {
        ArtifactoryDependenciesClient client = new ArtifactoryDependenciesClient(url, userName, password,
                new JenkinsBuildInfoLog(listener));
        client.setConnectionTimeout(timeout);
        if (!bypassProxy && proxyConfiguration != null) {
            client.setProxyConfiguration(proxyConfiguration.host, proxyConfiguration.port, proxyConfiguration.username,
                    proxyConfiguration.password);
        }

        return client;
    }

    /**
     * Decides what are the preferred credentials to use for resolving the repo keys of the server
     *
     * @return Preferred credentials for repo resolving. Never null.
     */
    public CredentialsConfig getResolvingCredentialsConfig(){
        if (resolverCredentialsConfig.isCredentialsProvided()) { // todo: the if so the the deployer credentials will be the one to return
            return getResolverCredentialsConfig();
        }
        if (deployerCredentialsConfig != null) {
            return getDeployerCredentialsConfig();
        }

        return new CredentialsConfig(null, null);
    }

    private void gatherUserPluginInfo(List<UserPluginInfo> infosToReturn, String pluginKey, DeployerOverrider deployerOverrider) {
        CredentialsConfig credentialsConfig = CredentialManager.getPreferredDeployer(deployerOverrider, this);
        ArtifactoryBuildInfoClient client = createArtifactoryClient(credentialsConfig.provideUsername(),
                credentialsConfig.getPassword(), createProxyConfiguration(Jenkins.getInstance().proxy));
        try {
            Map<String, List<Map>> userPluginInfo = client.getUserPluginInfo();
            if (userPluginInfo != null && userPluginInfo.containsKey(pluginKey)) {
                List<Map> stagingUserPluginInfo = userPluginInfo.get(pluginKey);
                if (stagingUserPluginInfo != null) {
                    for (Map stagingPluginInfo : stagingUserPluginInfo) {
                        infosToReturn.add(new UserPluginInfo(stagingPluginInfo));
                    }
                    Collections.sort(infosToReturn, new Comparator<UserPluginInfo>() {
                        public int compare(UserPluginInfo o1, UserPluginInfo o2) {
                            return o1.getPluginName().compareTo(o2.getPluginName());
                        }
                    });
                }
            }
        } catch (IOException e) {
            log.log(Level.WARNING, "Failed to obtain user plugin info: " + e.getMessage());
        } finally {
            client.shutdown();
        }
    }

    private static class RepositoryComparator implements Comparator<String>, Serializable {

        public int compare(String o1, String o2) {
            if (o1.contains("snapshot") && !o2.contains("snapshot")) {
                return 1;
            } else {
                return -1;
            }
        }
    }

    /**
     * Page Converter
     */
    public static final class ConverterImpl extends ArtifactoryServerConverter {
        public ConverterImpl(XStream2 xstream) {
            super(xstream);
        }
    }
}
