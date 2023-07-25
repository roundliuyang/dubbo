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
package org.apache.dubbo.rpc.model;

import org.apache.dubbo.common.config.Environment;
import org.apache.dubbo.common.context.ApplicationExt;
import org.apache.dubbo.common.deploy.ApplicationDeployer;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.extension.ExtensionScope;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.threadpool.manager.ExecutorRepository;
import org.apache.dubbo.common.utils.Assert;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.context.ConfigManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 表示正在使用Dubbo 的应用程序，并存储基本元数据信息，以便在RPC调用过程中使用。
 * ApplicationModel 包括许多关于发布服务的 ProviderModel 和许多关于订阅服务的 Consumer model
 *
 * {@link ExtensionLoader}, {@code DubboBootstrap} and this class are at present designed to be
 * singleton or static (by itself totally static or uses some static fields). So the instances
 * returned from them are of process scope. If you want to support multiple dubbo servers in one
 * single process, you may need to refactor those three classes.
 * <p>
 * Represent a application which is using Dubbo and store basic metadata info for using
 * during the processing of RPC invoking.
 * <p>
 * ApplicationModel includes many ProviderModel which is about published services
 * and many Consumer Model which is about subscribed services.
 * <p>
 */

public class ApplicationModel extends ScopeModel {
    protected static final Logger LOGGER = LoggerFactory.getLogger(ApplicationModel.class);
    public static final String NAME = "ApplicationModel";
    /**
     * 所有 ModuleModel 实例对象集合
     */
    private final List<ModuleModel> moduleModels = new CopyOnWriteArrayList<>();
    /**
     * 发布的ModuleModel实例对象集合
     */
    private final List<ModuleModel> pubModuleModels = new CopyOnWriteArrayList<>();
    /**
     * 环境信息 Environment 实例对象
     */
    private Environment environment;
    /**
     * 配置管理 ConfigManager 实例对象
     */
    private ConfigManager configManager;
    /**
     * 服务存储库ServiceRepository 实例对象
     */
    private ServiceRepository serviceRepository;
    /**
     * 应用程序部署器 ApplicationDeployer 实例对象
     */
    private ApplicationDeployer deployer;

    /**
     * 所属框架 FrameworkModel 实例对象
     */
    private final FrameworkModel frameworkModel;

    /**
     * 内部的模块模型ModuleModel 实例对象
     */
    private ModuleModel internalModule;

    /**
     * 默认的模块模型 ModuleModel 实例对象
     */
    private volatile ModuleModel defaultModule;

    // internal module index is 0, default module index is 1
    private AtomicInteger moduleIndex = new AtomicInteger(0);
    private Object moduleLock = new Object();

    // --------- static methods ----------//

    public static ApplicationModel ofNullable(ApplicationModel applicationModel) {
        if (applicationModel != null) {
            return applicationModel;
        } else {
            return defaultModel();
        }
    }

    /**
     * (应用程序领域模型)类型中获取默认模型对象的方法
     * 这里可以看到想要获取应用程序模型必须先通过框架领域模型来获取层级也是框架领域模型到应用程序领域模型
     * During destroying the default FrameworkModel, the FrameworkModel.defaultModel() or ApplicationModel.defaultModel()
     * will return a broken model, maybe cause unpredictable problem.
     * Recommendation: Avoid using the default model as much as possible.
     * @return the global default ApplicationModel
     */
    public static ApplicationModel defaultModel() {
        // should get from default FrameworkModel, avoid out of sync
        return FrameworkModel.defaultModel().defaultApplication();
    }

    /**
     * @deprecated use {@link ServiceRepository#allConsumerModels()}
     */
    @Deprecated
    public static Collection<ConsumerModel> allConsumerModels() {
        return defaultModel().getApplicationServiceRepository().allConsumerModels();
    }

    /**
     * @deprecated use {@link ServiceRepository#allProviderModels()}
     */
    @Deprecated
    public static Collection<ProviderModel> allProviderModels() {
        return defaultModel().getApplicationServiceRepository().allProviderModels();
    }

    /**
     * @deprecated use {@link FrameworkServiceRepository#lookupExportedService(String)}
     */
    @Deprecated
    public static ProviderModel getProviderModel(String serviceKey) {
        return defaultModel().getDefaultModule().getServiceRepository().lookupExportedService(serviceKey);
    }

    /**
     * @deprecated ConsumerModel should fetch from context
     */
    @Deprecated
    public static ConsumerModel getConsumerModel(String serviceKey) {
        return defaultModel().getDefaultModule().getServiceRepository().lookupReferredService(serviceKey);
    }

    /**
     * @deprecated Replace to {@link ScopeModel#getModelEnvironment()}
     */
    @Deprecated
    public static Environment getEnvironment() {
        return defaultModel().getModelEnvironment();
    }

    /**
     * @deprecated Replace to {@link ApplicationModel#getApplicationConfigManager()}
     */
    @Deprecated
    public static ConfigManager getConfigManager() {
        return defaultModel().getApplicationConfigManager();
    }

    /**
     * @deprecated Replace to {@link ApplicationModel#getApplicationServiceRepository()}
     */
    @Deprecated
    public static ServiceRepository getServiceRepository() {
        return defaultModel().getApplicationServiceRepository();
    }

    /**
     * @deprecated Replace to {@link ApplicationModel#getApplicationExecutorRepository()}
     */
    @Deprecated
    public static ExecutorRepository getExecutorRepository() {
        return defaultModel().getApplicationExecutorRepository();
    }

    /**
     * @deprecated Replace to {@link ApplicationModel#getCurrentConfig()}
     */
    @Deprecated
    public static ApplicationConfig getApplicationConfig() {
        return defaultModel().getCurrentConfig();
    }

    /**
     * @deprecated Replace to {@link ApplicationModel#getApplicationName()}
     */
    @Deprecated
    public static String getName() {
        return defaultModel().getCurrentConfig().getName();
    }

    /**
     * @deprecated Replace to {@link ApplicationModel#getApplicationName()}
     */
    @Deprecated
    public static String getApplication() {
        return getName();
    }

    // only for unit test
    @Deprecated
    public static void reset() {
        if (FrameworkModel.defaultModel().getDefaultAppModel() != null) {
            FrameworkModel.defaultModel().getDefaultAppModel().destroy();
        }
    }

    // ------------- instance methods ---------------//

    public ApplicationModel(FrameworkModel frameworkModel) {
        this(frameworkModel, false);
    }

    public ApplicationModel(FrameworkModel frameworkModel, boolean isInternal) {
        // 调用父类型ScopeModel传递参数,这个构造器的传递没与前面看到的FrameworkModel构造器的中的调用参数有些不同第一个参数我们为frameworkModel代表父域模型,
        // 第二个参数标记域为应用程序级别APPLICATION,第三个参数我们传递的为true代表为内部域
        super(frameworkModel, ExtensionScope.APPLICATION, isInternal);
        Assert.notNull(frameworkModel, "FrameworkModel can not be null");
        // 应用程序域成员变量记录frameworkModel对象
        this.frameworkModel = frameworkModel;
        // frameworkModel对象添加当前应用程序域对象
        frameworkModel.addApplication(this);
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info(getDesc() + " is created");
        }
        // 初始化应用程序
        initialize();
    }

    @Override
    protected void initialize() {
        // 这个是调用域模型来初始化基础信息如扩展访问器等,可以参考 3.2.5 ScopeModel类型的初始化方法initialize()章节
        super.initialize();
        // 创建一个内部的模块模型对象
        internalModule = new ModuleModel(this, true);
        // 创建一个独立服务存储对象
        this.serviceRepository = new ServiceRepository(this);

        // 获取应用程序初始化监听器ApplicationInitListener扩展
        ExtensionLoader<ApplicationInitListener> extensionLoader = this.getExtensionLoader(ApplicationInitListener.class);
        // 如果存在应用程序初始化监听器扩展则执行这个初始化方法,在当前的版本还未看到有具体的扩展实现类型
        Set<String> listenerNames = extensionLoader.getSupportedExtensions();
        for (String listenerName : listenerNames) {
            extensionLoader.getExtension(listenerName).init();
        }
        // 初始化扩展(这个是应用程序生命周期的方法调用,这里调用初始化方法
        initApplicationExts();

        // 获取域模型初始化器扩展对象列表,然后执行初始化方法
        ExtensionLoader<ScopeModelInitializer> initializerExtensionLoader = this.getExtensionLoader(ScopeModelInitializer.class);
        Set<ScopeModelInitializer> initializers = initializerExtensionLoader.getSupportedExtensionInstances();
        for (ScopeModelInitializer initializer : initializers) {
            initializer.initializeApplicationModel(this);
        }
    }

    private void initApplicationExts() {
        // ModuleModel中获取模块扩展对象
        Set<ApplicationExt> exts = this.getExtensionLoader(ApplicationExt.class).getSupportedExtensionInstances();
        for (ApplicationExt ext : exts) {
            ext.initialize();
        }
    }

    @Override
    protected void onDestroy() {
        // 1. remove from frameworkModel
        frameworkModel.removeApplication(this);

        // 2. pre-destroy, set stopping
        if (deployer != null) {
            // destroy registries and unregister services from registries first to notify consumers to stop consuming this instance.
            deployer.preDestroy();
        }

        // 3. Try to destroy protocols to stop this instance from receiving new requests from connections
        frameworkModel.tryDestroyProtocols();

        // 4. destroy application resources
        for (ModuleModel moduleModel : new ArrayList<>(moduleModels)) {
            if (moduleModel != internalModule) {
                moduleModel.destroy();
            }
        }
        // 5. destroy internal module later
        internalModule.destroy();

        // 6. post-destroy, release registry resources
        if (deployer != null) {
            deployer.postDestroy();
        }

        // 7. destroy other resources (e.g. ZookeeperTransporter )
        notifyDestroy();

        if (environment != null) {
            environment.destroy();
            environment = null;
        }
        if (configManager != null) {
            configManager.destroy();
            configManager = null;
        }
        if (serviceRepository != null) {
            serviceRepository.destroy();
            serviceRepository = null;
        }

        // 8. destroy framework if none application
        frameworkModel.tryDestroy();
    }

    public FrameworkModel getFrameworkModel() {
        return frameworkModel;
    }
    public ModuleModel newModule() {
        return new ModuleModel(this);
    }

    @Override
    public Environment getModelEnvironment() {
        if (environment == null) {
            // ApplicationModel中获Environment对象
            environment = (Environment) this.getExtensionLoader(ApplicationExt.class)
                .getExtension(Environment.NAME);
        }
        return environment;
    }

    public ConfigManager getApplicationConfigManager() {
        if (configManager == null) {
            configManager = (ConfigManager) this.getExtensionLoader(ApplicationExt.class)
                .getExtension(ConfigManager.NAME);
        }
        return configManager;
    }

    public ServiceRepository getApplicationServiceRepository() {
        return serviceRepository;
    }

    public ExecutorRepository getApplicationExecutorRepository() {
        return this.getExtensionLoader(ExecutorRepository.class).getDefaultExtension();
    }

    public ApplicationConfig getCurrentConfig() {
        return getApplicationConfigManager().getApplicationOrElseThrow();
    }

    public String getApplicationName() {
        return getCurrentConfig().getName();
    }

    public String tryGetApplicationName() {
        Optional<ApplicationConfig> appCfgOptional = getApplicationConfigManager().getApplication();
        return appCfgOptional.isPresent() ? appCfgOptional.get().getName() : null;
    }

    void addModule(ModuleModel moduleModel, boolean isInternal) {
        synchronized (moduleLock) {
            if (!this.moduleModels.contains(moduleModel)) {
                checkDestroyed();
                this.moduleModels.add(moduleModel);
                moduleModel.setInternalId(buildInternalId(getInternalId(), moduleIndex.getAndIncrement()));
                if (!isInternal) {
                    pubModuleModels.add(moduleModel);
                }
            }
        }
    }

    public void removeModule(ModuleModel moduleModel) {
        synchronized (moduleLock) {
            this.moduleModels.remove(moduleModel);
            this.pubModuleModels.remove(moduleModel);
            if (moduleModel == defaultModule) {
                defaultModule = findDefaultModule();
            }
        }
    }

    void tryDestroy() {
        if (this.moduleModels.isEmpty()
            || (this.moduleModels.size() == 1 && this.moduleModels.get(0) == internalModule)) {
            destroy();
        }
    }

    private void checkDestroyed() {
        if (isDestroyed()) {
            throw new IllegalStateException("ApplicationModel is destroyed");
        }
    }

    public List<ModuleModel> getModuleModels() {
        return Collections.unmodifiableList(moduleModels);
    }

    public List<ModuleModel> getPubModuleModels() {
        return Collections.unmodifiableList(pubModuleModels);
    }

    public ModuleModel getDefaultModule() {
        if (defaultModule == null) {
            synchronized (moduleLock) {
                if (defaultModule == null) {
                    defaultModule = findDefaultModule();
                    if (defaultModule == null) {
                        defaultModule = this.newModule();
                    }
                }
            }
        }
        return defaultModule;
    }

    private ModuleModel findDefaultModule() {
        for (ModuleModel moduleModel : moduleModels) {
            if (moduleModel != internalModule) {
                return moduleModel;
            }
        }
        return null;
    }

    public ModuleModel getInternalModule() {
        return internalModule;
    }

    /**
     * @deprecated only for ut
     */
    @Deprecated
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    /**
     * @deprecated only for ut
     */
    @Deprecated
    public void setConfigManager(ConfigManager configManager) {
        this.configManager = configManager;
    }

    /**
     * @deprecated only for ut
     */
    @Deprecated
    public void setServiceRepository(ServiceRepository serviceRepository) {
        this.serviceRepository = serviceRepository;
    }

    @Override
    public void addClassLoader(ClassLoader classLoader) {
        super.addClassLoader(classLoader);
        if (environment != null) {
            environment.refreshClassLoaders();
        }
    }

    @Override
    public void removeClassLoader(ClassLoader classLoader) {
        super.removeClassLoader(classLoader);
        if (environment != null) {
            environment.refreshClassLoaders();
        }
    }

    @Override
    protected boolean checkIfClassLoaderCanRemoved(ClassLoader classLoader) {
        return super.checkIfClassLoaderCanRemoved(classLoader) && !containsClassLoader(classLoader);
    }

    protected boolean containsClassLoader(ClassLoader classLoader) {
        return moduleModels.stream().anyMatch(moduleModel -> moduleModel.getClassLoaders().contains(classLoader));
    }

    public ApplicationDeployer getDeployer() {
        return deployer;
    }

    public void setDeployer(ApplicationDeployer deployer) {
        this.deployer = deployer;
    }
}
