/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.config.deploy;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.config.ConfigurationUtils;
import org.apache.dubbo.common.config.Environment;
import org.apache.dubbo.common.config.ReferenceCache;
import org.apache.dubbo.common.config.configcenter.DynamicConfiguration;
import org.apache.dubbo.common.config.configcenter.DynamicConfigurationFactory;
import org.apache.dubbo.common.config.configcenter.wrapper.CompositeDynamicConfiguration;
import org.apache.dubbo.common.deploy.AbstractDeployer;
import org.apache.dubbo.common.deploy.ApplicationDeployListener;
import org.apache.dubbo.common.deploy.ApplicationDeployer;
import org.apache.dubbo.common.deploy.DeployListener;
import org.apache.dubbo.common.deploy.DeployState;
import org.apache.dubbo.common.deploy.ModuleDeployer;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.lang.ShutdownHookCallbacks;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.threadpool.manager.ExecutorRepository;
import org.apache.dubbo.common.threadpool.manager.FrameworkExecutorRepository;
import org.apache.dubbo.common.utils.ArrayUtils;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.ConfigCenterConfig;
import org.apache.dubbo.config.DubboShutdownHook;
import org.apache.dubbo.config.MetadataReportConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.config.context.ConfigManager;
import org.apache.dubbo.config.utils.CompositeReferenceCache;
import org.apache.dubbo.config.utils.ConfigValidationUtils;
import org.apache.dubbo.metadata.report.MetadataReportFactory;
import org.apache.dubbo.metadata.report.MetadataReportInstance;
import org.apache.dubbo.registry.client.metadata.ServiceInstanceMetadataUtils;
import org.apache.dubbo.registry.support.RegistryManager;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.apache.dubbo.rpc.model.ModuleModel;
import org.apache.dubbo.rpc.model.ScopeModel;
import org.apache.dubbo.rpc.model.ScopeModelUtil;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static java.lang.String.format;
import static org.apache.dubbo.common.config.ConfigurationUtils.parseProperties;
import static org.apache.dubbo.common.constants.CommonConstants.REGISTRY_SPLIT_PATTERN;
import static org.apache.dubbo.common.constants.CommonConstants.REMOTE_METADATA_STORAGE_TYPE;
import static org.apache.dubbo.common.utils.StringUtils.isEmpty;
import static org.apache.dubbo.common.utils.StringUtils.isNotEmpty;
import static org.apache.dubbo.metadata.MetadataConstants.DEFAULT_METADATA_PUBLISH_DELAY;
import static org.apache.dubbo.metadata.MetadataConstants.METADATA_PUBLISH_DELAY_KEY;
import static org.apache.dubbo.remoting.Constants.CLIENT_KEY;

/**
 * initialize and start application instance
 */
public class DefaultApplicationDeployer extends AbstractDeployer<ApplicationModel> implements ApplicationDeployer {

    private static final Logger logger = LoggerFactory.getLogger(DefaultApplicationDeployer.class);

    private final ApplicationModel applicationModel;

    private final ConfigManager configManager;

    private final Environment environment;

    private final ReferenceCache referenceCache;

    private final FrameworkExecutorRepository frameworkExecutorRepository;
    private final ExecutorRepository executorRepository;

    private final AtomicBoolean hasPreparedApplicationInstance = new AtomicBoolean(false);
    private volatile boolean hasPreparedInternalModule = false;

    private ScheduledFuture<?> asyncMetadataFuture;
    private volatile CompletableFuture<Boolean> startFuture;
    private final DubboShutdownHook dubboShutdownHook;
    private final Object stateLock = new Object();
    private final Object startLock = new Object();
    private final Object destroyLock = new Object();
    private final Object internalModuleLock = new Object();

    public DefaultApplicationDeployer(ApplicationModel applicationModel) {
        super(applicationModel);
        this.applicationModel = applicationModel;
        configManager = applicationModel.getApplicationConfigManager();
        environment = applicationModel.getModelEnvironment();

        referenceCache = new CompositeReferenceCache(applicationModel);
        frameworkExecutorRepository = applicationModel.getFrameworkModel().getBeanFactory().getBean(FrameworkExecutorRepository.class);
        executorRepository = getExtensionLoader(ExecutorRepository.class).getDefaultExtension();
        dubboShutdownHook = new DubboShutdownHook(applicationModel);

        // load spi listener
        Set<ApplicationDeployListener> deployListeners = applicationModel.getExtensionLoader(ApplicationDeployListener.class)
            .getSupportedExtensionInstances();
        for (ApplicationDeployListener listener : deployListeners) {
            this.addDeployListener(listener);
        }
    }

    public static ApplicationDeployer get(ScopeModel moduleOrApplicationModel) {
        ApplicationModel applicationModel = ScopeModelUtil.getApplicationModel(moduleOrApplicationModel);
        ApplicationDeployer applicationDeployer = applicationModel.getDeployer();
        if (applicationDeployer == null) {
            applicationDeployer = applicationModel.getBeanFactory().getOrRegisterBean(DefaultApplicationDeployer.class);
        }
        return applicationDeployer;
    }

    @Override
    public ApplicationModel getApplicationModel() {
        return applicationModel;
    }

    private <T> ExtensionLoader<T> getExtensionLoader(Class<T> type) {
        return applicationModel.getExtensionLoader(type);
    }

    private void unRegisterShutdownHook() {
        dubboShutdownHook.unregister();
    }

    /**
     * Close registration of instance for pure Consumer process by setting registerConsumer to 'false'
     * by default is true.
     */
    private boolean isRegisterConsumerInstance() {
        Boolean registerConsumer = getApplication().getRegisterConsumer();
        if (registerConsumer == null) {
            return true;
        }
        return Boolean.TRUE.equals(registerConsumer);
    }

    @Override
    public ReferenceCache getReferenceCache() {
        return referenceCache;
    }

    /**
     * Initialize
     */
    @Override
    public void initialize() {
        // 状态判断，如果已经初始化过了就直接返回
        if (initialized) {
            return;
        }
        // Ensure that the initialization is completed when concurrent calls
        // 启动锁，确保在并发调用时完成初始化
        synchronized (startLock) {
            // 双重校验锁，如果已经初始化过了就直接返回
            if (initialized) {
                return;
            }
            // register shutdown hook
            // 注册关闭钩子，这个逻辑基本每个中间件应用都必须要要做的事情了，正常关闭应用回收资源，一般没这个逻辑情况下容易出现一些异常，让我们开发人员很疑惑，而这个逻辑往往并不好处理的干净。
            registerShutdownHook();

            // 启动配置中心，感觉Dubbo3耦合了这个玩意
            startConfigCenter();

            // 加载配置，一般配置信息当前机器的来源：环境变量，JVM启动参数，配置文字
            loadApplicationConfigs();

            // 初始化模块发布器 （发布服务提供和服务引用使用）
            initModuleDeployers();

            // @since 2.7.8
            // 启动元数据中心
            startMetadataCenter();

            // 初始化完成
            initialized = true;

            if (logger.isInfoEnabled()) {
                logger.info(getIdentifier() + " has been initialized!");
            }
        }
    }

    private void registerShutdownHook() {
        dubboShutdownHook.register();
    }

    private void initModuleDeployers() {
        // make sure created default module
        applicationModel.getDefaultModule();
        // copy modules and initialize avoid ConcurrentModificationException if add new module
        List<ModuleModel> moduleModels = new ArrayList<>(applicationModel.getModuleModels());
        for (ModuleModel moduleModel : moduleModels) {
            moduleModel.getDeployer().initialize();
        }
    }

    private void loadApplicationConfigs() {
        configManager.loadConfigs();
    }

    private void startConfigCenter() {

        // load application config
        // 加载应用程序配置 （配置可能有多个地方可以配置需要遵循Dubbo约定的优先级进行设置，也可能是多应用，多注册中心这样的配置）
        configManager.loadConfigsOfTypeFromProps(ApplicationConfig.class);

        // try set model name
        if (StringUtils.isBlank(applicationModel.getModelName())) {
            // 设置一下模块名字和模块描述（我们再Debug里面经常会看到这个描述信息 toString直接返回了Dubbo为我们改造的对象信息）
            applicationModel.setModelName(applicationModel.tryGetApplicationName());
        }

        // load config centers
        // 加载配置中心配置
        // 配置可能有多个地方可以配置需要遵循Dubbo约定的优先级进行设置，也可能是多应用，多注册中心这样的配置）
        configManager.loadConfigsOfTypeFromProps(ConfigCenterConfig.class);

        // 出于兼容性目的，如果没有明确指定配置中心，并且registryConfig的UseAConfigCenter为null或true，请使用registry作为默认配置中心
        useRegistryAsConfigCenterIfNecessary();

        // check Config Center
        // 配置管理器中获取配置中心
        Collection<ConfigCenterConfig> configCenters = configManager.getConfigCenters();
        // 配置中心配置不为空则刷新配置中心配置将其放入配置管理器中
        // 下面开始刷新配置中心配置,如果配置中心配置为空则执行空刷新
        if (CollectionUtils.isEmpty(configCenters)) {
            // 配置中心不存在的配置刷新
            ConfigCenterConfig configCenterConfig = new ConfigCenterConfig();
            configCenterConfig.setScopeModel(applicationModel);
            configCenterConfig.refresh();
            // 验证配置
            ConfigValidationUtils.validateConfigCenterConfig(configCenterConfig);
            if (configCenterConfig.isValid()) {
                // 配置合法则将配置放入配置管理器中
                configManager.addConfigCenter(configCenterConfig);
                configCenters = configManager.getConfigCenters();
            }
        } else {
            // 一个或者多个配置中心配置存在的情况下的配置刷新
            for (ConfigCenterConfig configCenterConfig : configCenters) {
                configCenterConfig.refresh();
                // 验证配置
                ConfigValidationUtils.validateConfigCenterConfig(configCenterConfig);
            }
        }

        // 配置中心配置不为空则将配置中心配置添加到environment中
        if (CollectionUtils.isNotEmpty(configCenters)) {
            // 多配置中心本地动态配置对象创建CompositeDynamicConfiguration
            CompositeDynamicConfiguration compositeDynamicConfiguration = new CompositeDynamicConfiguration();
            // 获取配置中心的相关配置
            for (ConfigCenterConfig configCenter : configCenters) {
                // Pass config from ConfigCenterBean to environment
                // 将配置中心的外部化配置,更新到环境里面
                environment.updateExternalConfigMap(configCenter.getExternalConfiguration());
                // 将配置中心的应用配置,添加到环境里面
                environment.updateAppExternalConfigMap(configCenter.getAppExternalConfiguration());

                // Fetch config from remote config center
                // 从配置中心拉取配置添加到组合配置中
                compositeDynamicConfiguration.addConfiguration(prepareEnvironment(configCenter));
            }
            // 将配置中心中的动态配置信息 设置到environment的动态配置属性中
            environment.setDynamicConfiguration(compositeDynamicConfiguration);
        }
    }

    private void startMetadataCenter() {

        // 如果未配置元数据中心的地址等配置则使用注册中心的地址等配置做为元数据中心的配置
        useRegistryAsMetadataCenterIfNecessary();

        // 获取应用的配置信息
        ApplicationConfig applicationConfig = getApplication();

        // 元数据配置类型 元数据类型， local 或 remote,，如果选择远程，则需要进一步指定元数据中心
        String metadataType = applicationConfig.getMetadataType();
        // FIXME, multiple metadata config support.
        Collection<MetadataReportConfig> metadataReportConfigs = configManager.getMetadataConfigs();
        if (CollectionUtils.isEmpty(metadataReportConfigs)) {
            // 这个就是判断 如果选择远程，则需要进一步指定元数据中心 否则就抛出来异常
            if (REMOTE_METADATA_STORAGE_TYPE.equals(metadataType)) {
                throw new IllegalStateException("No MetadataConfig found, Metadata Center address is required when 'metadata=remote' is enabled.");
            }
            return;
        }
        // MetadataReport实例的存储库对象获取
        MetadataReportInstance metadataReportInstance = applicationModel.getBeanFactory().getBean(MetadataReportInstance.class);
        List<MetadataReportConfig> validMetadataReportConfigs = new ArrayList<>(metadataReportConfigs.size());
        for (MetadataReportConfig metadataReportConfig : metadataReportConfigs) {
            ConfigValidationUtils.validateMetadataConfig(metadataReportConfig);
            validMetadataReportConfigs.add(metadataReportConfig);
        }
        // 初始化元数据
        metadataReportInstance.init(validMetadataReportConfigs);
        // MetadataReport实例的存储库对象初始化失败则抛出异常
        if (!metadataReportInstance.inited()) {
            throw new IllegalStateException(String.format("%s MetadataConfigs found, but none of them is valid.", metadataReportConfigs.size()));
        }
    }

    /**
     * For compatibility purpose, use registry as the default config center when
     * there's no config center specified explicitly and
     * useAsConfigCenter of registryConfig is null or true
     */
    private void useRegistryAsConfigCenterIfNecessary() {
        // we use the loading status of DynamicConfiguration to decide whether ConfigCenter has been initiated.
        // 我们使用DynamicConfiguration的加载状态来决定是否已启动ConfigCenter。配置中心配置加载完成之后会初始化动态配置defaultDynamicConfiguration
        if (environment.getDynamicConfiguration().isPresent()) {
            return;
        }

        // 从配置缓存中查询是否存在config-center相关配置 ,如果已经存在配置了就无需使用注册中心的配置地址直接返回
        if (CollectionUtils.isNotEmpty(configManager.getConfigCenters())) {
            return;
        }

        // load registry
        // 加载注册中心相关配置
        configManager.loadConfigsOfTypeFromProps(RegistryConfig.class);

        // 查询是否有注册中心设置了默认配置isDefault 设置为true的注册中心则为默认注册中心列表,如果没有注册中心设置为默认注册中心,则获取所有未设置默认配置的注册中心列
        List<RegistryConfig> defaultRegistries = configManager.getDefaultRegistries();
        // 存在注册中心
        if (defaultRegistries.size() > 0) {
            defaultRegistries
                .stream()
                // 判断当前注册中心是否可以作为配置中心
                .filter(this::isUsedRegistryAsConfigCenter)
                // 将注册中心配置映射转换为配置中心
                .map(this::registryAsConfigCenter)
                // 遍历配置中心流
                .forEach(configCenter -> {
                    if (configManager.getConfigCenter(configCenter.getId()).isPresent()) {
                        return;
                    }
                    // 配置管理器中添加配置中心,方便后去读取配置中心的配置信息
                    configManager.addConfigCenter(configCenter);
                    logger.info("use registry as config-center: " + configCenter);

                });
        }
    }

    private boolean isUsedRegistryAsConfigCenter(RegistryConfig registryConfig) {
        return isUsedRegistryAsCenter(registryConfig, registryConfig::getUseAsConfigCenter, "config",
            DynamicConfigurationFactory.class);
    }

    private ConfigCenterConfig registryAsConfigCenter(RegistryConfig registryConfig) {
        // 注册中心协议获取这里例子中的是zookeeper协议
        String protocol = registryConfig.getProtocol();
        // 注册中心端口 2181
        Integer port = registryConfig.getPort();
        // 在Dubbo中配置信息 很多情况下都以URL形式表示,这里转换后的地址为zookeeper://127.0.0.1:2181
        URL url = URL.valueOf(registryConfig.getAddress(), registryConfig.getScopeModel());
        // 生成当前配置中心的id 封装之后的内容为:
        // config-center-zookeeper-127.0.0.1-2181
        String id = "config-center-" + protocol + "-" + url.getHost() + "-" + port;
        // 配置中心配置对象创建
        ConfigCenterConfig cc = new ConfigCenterConfig();
        // config-center-zookeeper-127.0.0.1-2181
        cc.setId(id);
        cc.setScopeModel(applicationModel);
        if (cc.getParameters() == null) {
            cc.setParameters(new HashMap<>());
        }
        if (CollectionUtils.isNotEmptyMap(registryConfig.getParameters())) {
            cc.getParameters().putAll(registryConfig.getParameters()); // copy the parameters
        }
        cc.getParameters().put(CLIENT_KEY, registryConfig.getClient());
        // zookeeper
        cc.setProtocol(protocol);
        // 2181
        cc.setPort(port);
        if (StringUtils.isNotEmpty(registryConfig.getGroup())) {
            cc.setGroup(registryConfig.getGroup());
        }
        // 这个方法转换地址是修复bug用的可以看bug https://github.com/apache/dubbo/issues/6476
        cc.setAddress(getRegistryCompatibleAddress(registryConfig));
        // 注册中心分组做为配置中心命名空间 这里为null
        cc.setNamespace(registryConfig.getGroup());
        // zk认证信息
        cc.setUsername(registryConfig.getUsername());
        // zk认证信息
        cc.setPassword(registryConfig.getPassword());
        if (registryConfig.getTimeout() != null) {
            cc.setTimeout(registryConfig.getTimeout().longValue());
        }
        // 这个属性注释中已经建议了已经弃用了默认就是false了
        // 如果配置中心被赋予最高优先级，它将覆盖所有其他配置，
        cc.setHighestPriority(false);
        return cc;
    }

    private void useRegistryAsMetadataCenterIfNecessary() {

        // 配置缓存中查询元数据配置
        Collection<MetadataReportConfig> metadataConfigs = configManager.getMetadataConfigs();

        // 配置存在则直接返回
        if (CollectionUtils.isNotEmpty(metadataConfigs)) {
            return;
        }

        // 查询是否有注册中心设置了默认配置isDefault 设置为true的注册中心则为默认注册中心列表,
        // 如果没有注册中心设置为默认注册中心,则获取所有未设置默认配置的注册中心列表
        List<RegistryConfig> defaultRegistries = configManager.getDefaultRegistries();
        if (defaultRegistries.size() > 0) {
            // 多注册中心遍历
            defaultRegistries
                .stream()
                // 筛选符合条件的注册中心(筛选逻辑就是查看是否有对应协议的扩展支持)
                .filter(this::isUsedRegistryAsMetadataCenter)
                // 注册中心配置映射为元数据中心  映射就是获取需要的配置
                .map(this::registryAsMetadataCenter)
                // 将元数据中心配置存储在配置缓存中方便后续使用
                .forEach(metadataReportConfig -> {
                    if (metadataReportConfig.getId() == null) {
                        Collection<MetadataReportConfig> metadataReportConfigs = configManager.getMetadataConfigs();
                        if (CollectionUtils.isNotEmpty(metadataReportConfigs)) {
                            for (MetadataReportConfig existedConfig : metadataReportConfigs) {
                                if (existedConfig.getId() == null && existedConfig.getAddress().equals(metadataReportConfig.getAddress())) {
                                    return;
                                }
                            }
                        }
                        configManager.addMetadataReport(metadataReportConfig);
                    } else {
                        Optional<MetadataReportConfig> configOptional = configManager.getConfig(MetadataReportConfig.class, metadataReportConfig.getId());
                        if (configOptional.isPresent()) {
                            return;
                        }
                        configManager.addMetadataReport(metadataReportConfig);
                    }
                    logger.info("use registry as metadata-center: " + metadataReportConfig);
                });
        }
    }

    private boolean isUsedRegistryAsMetadataCenter(RegistryConfig registryConfig) {
        return isUsedRegistryAsCenter(registryConfig, registryConfig::getUseAsMetadataCenter, "metadata",
            MetadataReportFactory.class);
    }

    /**
     * Is used the specified registry as a center infrastructure
     *
     * @param registryConfig       the {@link RegistryConfig}
     * @param usedRegistryAsCenter the configured value on
     * @param centerType           the type name of center
     * @param extensionClass       an extension class of a center infrastructure
     * @return
     * @since 2.7.8
     */
    private boolean isUsedRegistryAsCenter(RegistryConfig registryConfig, Supplier<Boolean> usedRegistryAsCenter,
                                           String centerType,
                                           Class<?> extensionClass) {
        final boolean supported;
        // 这个useAsConfigCenter参数是来自注册中心的配置 如果配置了这个值则以这个值为准,如果配置了false则这个注册中心不能做为配置中心
        Boolean configuredValue = usedRegistryAsCenter.get();
        if (configuredValue != null) { // If configured, take its value.
            supported = configuredValue.booleanValue();
        } else {                       // Or check the extension existence
            // 这个逻辑的话是判断下注册中心的协议是否满足要求,我们例子代码中使用的是zookeeper
            String protocol = registryConfig.getProtocol();
            // 这个扩展是否支持的逻辑判断是这样的扫描扩展类 看一下当前扩展类型是否有对应协议的扩展 比如在扩展文件里面这样配置过后是支持的 protocol=xxxImpl
            // 动态配置的扩展类型为:interface org.apache.dubbo.common.config.configcenter.DynamicConfigurationFactory
            // zookeeper协议肯定是支持的因为zookeeper协议实现了这个动态配置工厂 ,这个扩展类型为ZookeeperDynamicConfigurationFactory
            // 代码位置在dubbo-configcenter-zookeeper包中的org.apache.dubbo.common.config.configcenter.DynamicConfigurationFactory扩展配置中内容为zookeeper=org.apache.dubbo.configcenter.support.zookeeper.ZookeeperDynamicConfigurationFactory
            supported = supportsExtension(extensionClass, protocol);
            // 配置中心走注册中心会打印一条日志
            if (logger.isInfoEnabled()) {
                logger.info(format("No value is configured in the registry, the %s extension[name : %s] %s as the %s center"
                    , extensionClass.getSimpleName(), protocol, supported ? "supports" : "does not support", centerType));
            }
        }

        // 配置中心走注册中心会打印一条日志
        if (logger.isInfoEnabled()) {
            logger.info(format("The registry[%s] will be %s as the %s center", registryConfig,
                supported ? "used" : "not used", centerType));
        }
        return supported;
    }

    /**
     * Supports the extension with the specified class and name
     *
     * @param extensionClass the {@link Class} of extension
     * @param name           the name of extension
     * @return if supports, return <code>true</code>, or <code>false</code>
     * @since 2.7.8
     */
    private boolean supportsExtension(Class<?> extensionClass, String name) {
        if (isNotEmpty(name)) {
            ExtensionLoader extensionLoader = getExtensionLoader(extensionClass);
            return extensionLoader.hasExtension(name);
        }
        return false;
    }

    private MetadataReportConfig registryAsMetadataCenter(RegistryConfig registryConfig) {
        String protocol = registryConfig.getProtocol();
        URL url = URL.valueOf(registryConfig.getAddress(), registryConfig.getScopeModel());
        MetadataReportConfig metadataReportConfig = new MetadataReportConfig();
        metadataReportConfig.setId(registryConfig.getId());
        metadataReportConfig.setScopeModel(applicationModel);
        if (metadataReportConfig.getParameters() == null) {
            metadataReportConfig.setParameters(new HashMap<>());
        }
        if (CollectionUtils.isNotEmptyMap(registryConfig.getParameters())) {
            metadataReportConfig.getParameters().putAll(registryConfig.getParameters()); // copy the parameters
        }
        metadataReportConfig.getParameters().put(CLIENT_KEY, registryConfig.getClient());
        metadataReportConfig.setGroup(registryConfig.getGroup());
        metadataReportConfig.setAddress(getRegistryCompatibleAddress(registryConfig));
        metadataReportConfig.setUsername(registryConfig.getUsername());
        metadataReportConfig.setPassword(registryConfig.getPassword());
        metadataReportConfig.setTimeout(registryConfig.getTimeout());
        return metadataReportConfig;
    }

    private String getRegistryCompatibleAddress(RegistryConfig registryConfig) {
        String registryAddress = registryConfig.getAddress();
        String[] addresses = REGISTRY_SPLIT_PATTERN.split(registryAddress);
        if (ArrayUtils.isEmpty(addresses)) {
            throw new IllegalStateException("Invalid registry address found.");
        }
        String address = addresses[0];
        // since 2.7.8
        // Issue : https://github.com/apache/dubbo/issues/6476
        StringBuilder metadataAddressBuilder = new StringBuilder();
        URL url = URL.valueOf(address, registryConfig.getScopeModel());
        String protocolFromAddress = url.getProtocol();
        if (isEmpty(protocolFromAddress)) {
            // If the protocol from address is missing, is like :
            // "dubbo.registry.address = 127.0.0.1:2181"
            String protocolFromConfig = registryConfig.getProtocol();
            metadataAddressBuilder.append(protocolFromConfig).append("://");
        }
        metadataAddressBuilder.append(address);
        return metadataAddressBuilder.toString();
    }

    /**
     * 发布器是帮助我们发布服务和引用服务的，在Dubbo3中不论是服务提供者还是服务消费者如果想要启动服务都需要走这个启动方法的逻辑
     * Start the bootstrap
     *
     * @return
     */
    @Override
    public Future start() {
        // 启动锁，防止重复启动
        synchronized (startLock) {
            // 发布器，状态已经设置为停止或者失败了就直接抛出异常
            if (isStopping() || isStopped() || isFailed()) {
                throw new IllegalStateException(getIdentifier() + " is stopping or stopped, can not start again");
            }

            try {
                // maybe call start again after add new module, check if any new module
                // 可能在添加新模块后再次调用start，检查是否有任何新模块
                // 这里遍历当前应用程序下的所有模块如果某个模块是PENDING状态则这里hasPendingModule的值为true
                boolean hasPendingModule = hasPendingModule();

                // 发布器状态正在启动中
                if (isStarting()) {
                    // currently, is starting, maybe both start by module and application
                    // if it has new modules, start them
                    if (hasPendingModule) {
                        // 启动模块
                        startModules();
                    }
                    // if it is starting, reuse previous startFuture
                    // 模块异步启动中
                    return startFuture;
                }

                // if is started and no new module, just return
                // 如果已启动且没有新模块，直接返回
                if (isStarted() && !hasPendingModule) {
                    return CompletableFuture.completedFuture(false);
                }

                // pending -> starting : first start app
                // started -> starting : re-start app
                // 启动状态切换，将启动状态切换到STARTING（pending和started状态无需切换）
                onStarting();

                // 核心初始化逻辑，这里主要做一些应用级别启动比如配置中心，元数据中心
                initialize();

                // 启动模块（我们的服务提供和服务引用是在这个模块级别的）
                doStart();
            } catch (Throwable e) {
                onFailed(getIdentifier() + " start failure", e);
                throw e;
            }

            return startFuture;
        }
    }

    private boolean hasPendingModule() {
        boolean found = false;
        for (ModuleModel moduleModel : applicationModel.getModuleModels()) {
            if (moduleModel.getDeployer().isPending()) {
                found = true;
                break;
            }
        }
        return found;
    }

    @Override
    public Future getStartFuture() {
        return startFuture;
    }

    private void doStart() {
        startModules();

        // prepare application instance
//        prepareApplicationInstance();

        // Ignore checking new module after start
//        executorRepository.getSharedExecutor().submit(() -> {
//            try {
//                while (isStarting()) {
//                    // notify when any module state changed
//                    synchronized (stateLock) {
//                        try {
//                            stateLock.wait(500);
//                        } catch (InterruptedException e) {
//                            // ignore
//                        }
//                    }
//
//                    // if has new module, do start again
//                    if (hasPendingModule()) {
//                        startModules();
//                    }
//                }
//            } catch (Throwable e) {
//                onFailed(getIdentifier() + " check start occurred an exception", e);
//            }
//        });
    }

    private void startModules() {
        // ensure init and start internal module first
        // 确保初始化并首先启动内部模块,Dubbo3中将模块分为内部和外部，内部是核心代码已经提供的一些服务比如元数据服务，外部是我们自己写的服务
        prepareInternalModule();

        // filter and start pending modules, ignore new module during starting, throw exception of module start
        // 启动所有的模块 （启动所有的服务）
        for (ModuleModel moduleModel : new ArrayList<>(applicationModel.getModuleModels())) {
            // 这个状态默认就是PENDING的
            if (moduleModel.getDeployer().isPending()) {
                // 模块启动器，发布服务
                moduleModel.getDeployer().start();
            }
        }
    }

    @Override
    public void prepareApplicationInstance() {
        if (hasPreparedApplicationInstance.get()) {
            return;
        }

        if (isRegisterConsumerInstance()) {
            exportMetadataService();
            if (hasPreparedApplicationInstance.compareAndSet(false, true)) {
                // register the local ServiceInstance if required
                registerServiceInstance();
            }
        }
    }

    public void prepareInternalModule() {
        if(hasPreparedInternalModule){
            return;
        }
        synchronized (internalModuleLock) {
            if (hasPreparedInternalModule) {
                return;
            }

            // start internal module
            ModuleDeployer internalModuleDeployer = applicationModel.getInternalModule().getDeployer();
            if (!internalModuleDeployer.isStarted()) {
                Future future = internalModuleDeployer.start();
                // wait for internal module startup
                try {
                    future.get(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    logger.warn("wait for internal module startup failed: " + e.getMessage(), e);
                }
            }
        }
    }

    private boolean hasExportedServices() {
        for (ModuleModel moduleModel : applicationModel.getModuleModels()) {
            if (CollectionUtils.isNotEmpty(moduleModel.getConfigManager().getServices())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isBackground() {
        for (ModuleModel moduleModel : applicationModel.getModuleModels()) {
            if (moduleModel.getDeployer().isBackground()) {
                return true;
            }
        }
        return false;
    }

    private DynamicConfiguration prepareEnvironment(ConfigCenterConfig configCenter) {
        if (configCenter.isValid()) {
            if (!configCenter.checkOrUpdateInitialized(true)) {
                return null;
            }

            DynamicConfiguration dynamicConfiguration;
            try {
                dynamicConfiguration = getDynamicConfiguration(configCenter.toUrl());
            } catch (Exception e) {
                if (!configCenter.isCheck()) {
                    logger.warn("The configuration center failed to initialize", e);
                    configCenter.setInitialized(false);
                    return null;
                } else {
                    throw new IllegalStateException(e);
                }
            }

            if (StringUtils.isNotEmpty(configCenter.getConfigFile())) {
                String configContent = dynamicConfiguration.getProperties(configCenter.getConfigFile(), configCenter.getGroup());
                String appGroup = getApplication().getName();
                String appConfigContent = null;
                if (isNotEmpty(appGroup)) {
                    appConfigContent = dynamicConfiguration.getProperties
                        (isNotEmpty(configCenter.getAppConfigFile()) ? configCenter.getAppConfigFile() : configCenter.getConfigFile(),
                            appGroup
                        );
                }
                try {
                    environment.updateExternalConfigMap(parseProperties(configContent));
                    environment.updateAppExternalConfigMap(parseProperties(appConfigContent));
                } catch (IOException e) {
                    throw new IllegalStateException("Failed to parse configurations from Config Center.", e);
                }
            }
            return dynamicConfiguration;
        }
        return null;
    }

    /**
     * Get the instance of {@link DynamicConfiguration} by the specified connection {@link URL} of config-center
     *
     * @param connectionURL of config-center
     * @return non-null
     * @since 2.7.5
     */
    private DynamicConfiguration getDynamicConfiguration(URL connectionURL) {
        String protocol = connectionURL.getProtocol();

        DynamicConfigurationFactory factory = ConfigurationUtils.getDynamicConfigurationFactory(applicationModel, protocol);
        return factory.getDynamicConfiguration(connectionURL);
    }

    private volatile boolean registered;

    private void registerServiceInstance() {
        try {
            registered = true;
            ServiceInstanceMetadataUtils.registerMetadataAndInstance(applicationModel);
        } catch (Exception e) {
            logger.error("Register instance error", e);
        }
        if (registered) {
            // scheduled task for updating Metadata and ServiceInstance
            asyncMetadataFuture = frameworkExecutorRepository.getSharedScheduledExecutor().scheduleWithFixedDelay(() -> {

                // ignore refresh metadata on stopping
                if (applicationModel.isDestroyed()) {
                    return;
                }
                try {
                    if (!applicationModel.isDestroyed() && registered) {
                        ServiceInstanceMetadataUtils.refreshMetadataAndInstance(applicationModel);
                    }
                } catch (Exception e) {
                    if (!applicationModel.isDestroyed()) {
                        logger.error("Refresh instance and metadata error", e);
                    }
                }
            }, 0, ConfigurationUtils.get(applicationModel, METADATA_PUBLISH_DELAY_KEY, DEFAULT_METADATA_PUBLISH_DELAY), TimeUnit.MILLISECONDS);
        }
    }

    private void unregisterServiceInstance() {
        if (registered) {
            ServiceInstanceMetadataUtils.unregisterMetadataAndInstance(applicationModel);
        }
    }

    @Override
    public void stop() {
        applicationModel.destroy();
    }

    @Override
    public void preDestroy() {
        synchronized (destroyLock) {
            if (isStopping() || isStopped()) {
                return;
            }
            onStopping();

            destroyRegistries();
            destroyServiceDiscoveries();
            destroyMetadataReports();

            unRegisterShutdownHook();
            if (asyncMetadataFuture != null) {
                asyncMetadataFuture.cancel(true);
            }
            unregisterServiceInstance();
        }
    }

    @Override
    public void postDestroy() {
        synchronized (destroyLock) {
            // expect application model is destroyed before here
            if (isStopped()) {
                return;
            }
            try {
                executeShutdownCallbacks();

                // TODO should we close unused protocol server which only used by this application?
                // protocol server will be closed on all applications of same framework are stopped currently, but no associate to application
                // see org.apache.dubbo.config.deploy.FrameworkModelCleaner#destroyProtocols
                // see org.apache.dubbo.config.bootstrap.DubboBootstrapMultiInstanceTest#testMultiProviderApplicationStopOneByOne

                // destroy all executor services
                destroyExecutorRepository();

                onStopped();
            } catch (Throwable ex) {
                String msg = getIdentifier() + " an error occurred while stopping application: " + ex.getMessage();
                onFailed(msg, ex);
            }
        }
    }

    private void executeShutdownCallbacks() {
        ShutdownHookCallbacks shutdownHookCallbacks = applicationModel.getBeanFactory().getBean(ShutdownHookCallbacks.class);
        shutdownHookCallbacks.callback();
    }

    @Override
    public void notifyModuleChanged(ModuleModel moduleModel, DeployState state) {
        checkState(moduleModel, state);

        // notify module state changed or module changed
        synchronized (stateLock) {
            stateLock.notifyAll();
        }
    }

    @Override
    public void checkState(ModuleModel moduleModel, DeployState moduleState) {
        synchronized (stateLock) {
            if (!moduleModel.isInternal() && moduleState == DeployState.STARTED) {
                prepareApplicationInstance();
            }
            DeployState newState = calculateState();
            switch (newState) {
                case STARTED:
                    onStarted();
                    break;
                case STARTING:
                    onStarting();
                    break;
                case STOPPING:
                    onStopping();
                    break;
                case STOPPED:
                    onStopped();
                    break;
                case FAILED:
                    Throwable error = null;
                    ModuleModel errorModule = null;
                    for (ModuleModel module : applicationModel.getModuleModels()) {
                        ModuleDeployer deployer = module.getDeployer();
                        if (deployer.isFailed() && deployer.getError() != null) {
                            error = deployer.getError();
                            errorModule = module;
                            break;
                        }
                    }
                    onFailed(getIdentifier() + " found failed module: " + errorModule.getDesc(), error);
                    break;
                case PENDING:
                    // cannot change to pending from other state
                    // setPending();
                    break;
            }
        }
    }

    private DeployState calculateState() {
        DeployState newState = DeployState.UNKNOWN;
        int pending = 0, starting = 0, started = 0, stopping = 0, stopped = 0, failed = 0;
        for (ModuleModel moduleModel : applicationModel.getModuleModels()) {
            ModuleDeployer deployer = moduleModel.getDeployer();
            if (deployer == null) {
                pending++;
            } else if (deployer.isPending()) {
                pending++;
            } else if (deployer.isStarting()) {
                starting++;
            } else if (deployer.isStarted()) {
                started++;
            } else if (deployer.isStopping()) {
                stopping++;
            } else if (deployer.isStopped()) {
                stopped++;
            } else if (deployer.isFailed()) {
                failed++;
            }
        }

        if (failed > 0) {
            newState = DeployState.FAILED;
        } else if (started > 0) {
            if (pending + starting + stopping + stopped == 0) {
                // all modules have been started
                newState = DeployState.STARTED;
            } else if (pending + starting > 0) {
                // some module is pending and some is started
                newState = DeployState.STARTING;
            } else if (stopping + stopped > 0) {
                newState = DeployState.STOPPING;
            }
        } else if (starting > 0) {
            // any module is starting
            newState = DeployState.STARTING;
        } else if (pending > 0) {
            if (starting + starting + stopping + stopped == 0) {
                // all modules have not starting or started
                newState = DeployState.PENDING;
            } else if (stopping + stopped > 0) {
                // some is pending and some is stopping or stopped
                newState = DeployState.STOPPING;
            }
        } else if (stopping > 0) {
            // some is stopping and some stopped
            newState = DeployState.STOPPING;
        } else if (stopped > 0) {
            // all modules are stopped
            newState = DeployState.STOPPED;
        }
        return newState;
    }

    private void exportMetadataService() {
        if (!isStarting()) {
            return;
        }
        for (DeployListener<ApplicationModel> listener : listeners) {
            try {
                if (listener instanceof ApplicationDeployListener) {
                    ((ApplicationDeployListener) listener).onModuleStarted(applicationModel);
                }
            } catch (Throwable e) {
                logger.error(getIdentifier() + " an exception occurred when handle starting event", e);
            }
        }
    }

    private void onStarting() {
        // pending -> starting
        // started -> starting
        if (!(isPending() || isStarted())) {
            return;
        }
        setStarting();
        startFuture = new CompletableFuture();
        if (logger.isInfoEnabled()) {
            logger.info(getIdentifier() + " is starting.");
        }
    }

    private void onStarted() {
        try {
            // starting -> started
            if (!isStarting()) {
                return;
            }
            setStarted();
            if (logger.isInfoEnabled()) {
                logger.info(getIdentifier() + " is ready.");
            }
            // refresh metadata
            try {
                if (registered) {
                    ServiceInstanceMetadataUtils.refreshMetadataAndInstance(applicationModel);
                }
            } catch (Exception e) {
                logger.error("refresh meta and instance failed: " + e.getMessage(), e);
            }
        } finally {
            // complete future
            completeStartFuture(true);
        }
    }

    private void completeStartFuture(boolean success) {
        if (startFuture != null) {
            startFuture.complete(success);
        }
    }

    private void onStopping() {
        try {
            if (isStopping() || isStopped()) {
                return;
            }
            setStopping();
            if (logger.isInfoEnabled()) {
                logger.info(getIdentifier() + " is stopping.");
            }
        } finally {
            completeStartFuture(false);
        }
    }

    private void onStopped() {
        try {
            if (isStopped()) {
                return;
            }
            setStopped();
            if (logger.isInfoEnabled()) {
                logger.info(getIdentifier() + " has stopped.");
            }
        } finally {
            completeStartFuture(false);
        }
    }

    private void onFailed(String msg, Throwable ex) {
        try {
            setFailed(ex);
            logger.error(msg, ex);
        } finally {
            completeStartFuture(false);
        }
    }

    private void destroyExecutorRepository() {
        // shutdown export/refer executor
        executorRepository.shutdownServiceExportExecutor();
        executorRepository.shutdownServiceReferExecutor();
        getExtensionLoader(ExecutorRepository.class).getDefaultExtension().destroyAll();
    }

    private void destroyRegistries() {
        RegistryManager.getInstance(applicationModel).destroyAll();
    }

    private void destroyServiceDiscoveries() {
        RegistryManager.getInstance(applicationModel).getServiceDiscoveries().forEach(serviceDiscovery -> {
            try {
                serviceDiscovery.destroy();
            } catch (Throwable ignored) {
                logger.warn(ignored.getMessage(), ignored);
            }
        });
        if (logger.isDebugEnabled()) {
            logger.debug(getIdentifier() + "'s all ServiceDiscoveries have been destroyed.");
        }
    }

    private void destroyMetadataReports() {
        // only destroy MetadataReport of this application
        List<MetadataReportFactory> metadataReportFactories = getExtensionLoader(MetadataReportFactory.class).getLoadedExtensionInstances();
        for (MetadataReportFactory metadataReportFactory : metadataReportFactories) {
            metadataReportFactory.destroy();
        }
    }

    private ApplicationConfig getApplication() {
        return configManager.getApplicationOrElseThrow();
    }

}
