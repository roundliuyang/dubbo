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
import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.extension.ExtensionScope;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.resource.GlobalResourcesRepository;
import org.apache.dubbo.common.utils.Assert;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.metadata.definition.TypeDefinitionBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * dubbo 框架模型，可与多个应用程序共享
 *
 * 了解了这几个模型对象的关系我们可以了解到这几个模型对象的管理层级从框架到应用程序,然后到模块的管理(FrameworkModel->ApplicationModel->ModuleModel),
 * 他们主要用来针对框架,应用程序,模块的存储,发布管理,,配置管理
 * Model of dubbo framework, it can be shared with multiple applications.
 */
public class FrameworkModel extends ScopeModel {

    protected static final Logger LOGGER = LoggerFactory.getLogger(FrameworkModel.class);

    public static final String NAME = "FrameworkModel";
    private static final AtomicLong index = new AtomicLong(1);
    // internal app index is 0, default app index is 1
    private final AtomicLong appIndex = new AtomicLong(0);

    private static Object globalLock = new Object();
    
    private volatile static FrameworkModel defaultInstance;

    private volatile ApplicationModel defaultAppModel;

    /**
     * FrameworkModel 实例对象集合
     */
    private static List<FrameworkModel> allInstances = new CopyOnWriteArrayList<>();

    /**
     * 所有 ApplicationModel 实例对象集合
     */
    private List<ApplicationModel> applicationModels = new CopyOnWriteArrayList<>();

    /**
     * 发布的ApplicationModel实例对象集合
     */
    private List<ApplicationModel> pubApplicationModels = new CopyOnWriteArrayList<>();

    /**
     * 框架的服务存储库FrameworkServiceRepository类型对象(数据存储在内存中)
     */
    private FrameworkServiceRepository serviceRepository;

    /**
     * 内部的应用程序模型对象 internalApplicationModel
     */
    private ApplicationModel internalApplicationModel;

    private Object instLock = new Object();

    public FrameworkModel() {
        // 调用父类型ScopeModel传递参数，这个构造器的第一个参数为空代表这是一个顶层的域模型，第二个代表了这个是框架FRAMEWORK域，第三个false不是内部域
        super(null, ExtensionScope.FRAMEWORK, false);

        //内部id用于表示模型树的层次结构，如层次结构:
        //FrameworkModel（索引=1）->ApplicationModel（索引=2）->ModuleModel（索引=1，第一个用户模块）
        //这个index变量是static类型的为静态全局变量默认值从1开始，如果有多个框架模型对象则internalId编号从1开始依次递增
        this.setInternalId(String.valueOf(index.getAndIncrement()));
        // register FrameworkModel instance early
        // 将当前新创建的框架实例对象添加到容器中
        synchronized (globalLock) {
            // 将当前框架模型实例添加到所有框架模型缓存对象中
            allInstances.add(this);
            // 如上面代码所示重置默认的框架模型对象,这里将会是缓存实例列表的第一个,新增了一个刷新默认实例对象
            resetDefaultFrameworkModel();
        }
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info(getDesc() + " is created");
        }
        // 初始化框架模型领域对象
        initialize();
    }

    @Override
    protected void initialize() {
        //这里初始化之前先调用下父类型ScopeModel的初始化方法
        super.initialize();

        // 使用TypeDefinitionBuilder的静态方法initBuilders来初始化类型构建器TypeBuilder类型集合
        TypeDefinitionBuilder.initBuilders(this);

        // 框架服务存储仓库对象,可以用于快速查询服务提供者信息
        serviceRepository = new FrameworkServiceRepository(this);

        // 获取ScopeModelInitializer类型(域模型初始化器)的扩展加载器ExtensionLoader,每个扩展类型都会创建一个扩展加载器缓存起来
        ExtensionLoader<ScopeModelInitializer> initializerExtensionLoader = this.getExtensionLoader(ScopeModelInitializer.class);
        // 获取ScopeModelInitializer类型的支持的扩展集合,这里当前版本存在8个扩展类型实现
        Set<ScopeModelInitializer> initializers = initializerExtensionLoader.getSupportedExtensionInstances();
        // 遍历这些扩展实现调用他们的initializeFrameworkModel方法来传递FrameworkModel类型对象,细节我们待会再详细说下
        for (ScopeModelInitializer initializer : initializers) {
            initializer.initializeFrameworkModel(this);
        }

        // 创建一个内部的ApplicationModel类型,细节下面说
        internalApplicationModel = new ApplicationModel(this, true);
        // 创建ApplicationConfig类型对象同时传递应用程序模型对象internalApplicationModel
        // 获取ConfigManager类型对象,然后设置添加当前应用配置对象
        internalApplicationModel.getApplicationConfigManager().setApplication(
            new ApplicationConfig(internalApplicationModel, CommonConstants.DUBBO_INTERNAL_APPLICATION));
        // 设置公开的模块名字为常量DUBBO_INTERNAL_APPLICATION
        internalApplicationModel.setModelName(CommonConstants.DUBBO_INTERNAL_APPLICATION);
    }

    @Override
    protected void onDestroy() {
        if (defaultInstance == this) {
            // NOTE: During destroying the default FrameworkModel, the FrameworkModel.defaultModel() or ApplicationModel.defaultModel()
            // will return a broken model, maybe cause unpredictable problem.
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("Destroying default framework model: " + getDesc());
            }
        }

        if (LOGGER.isInfoEnabled()) {
            LOGGER.info(getDesc() + " is destroying ...");
        }

        // destroy all application model
        for (ApplicationModel applicationModel : new ArrayList<>(applicationModels)) {
            applicationModel.destroy();
        }
        // check whether all application models are destroyed
        checkApplicationDestroy();

        // notify destroy and clean framework resources
        // see org.apache.dubbo.config.deploy.FrameworkModelCleaner
        notifyDestroy();
        checkApplicationDestroy();

        if (LOGGER.isInfoEnabled()) {
            LOGGER.info(getDesc() + " is destroyed");
        }

        // remove from allInstances and reset default FrameworkModel
        synchronized (globalLock) {
            allInstances.remove(this);
            resetDefaultFrameworkModel();
        }

        // if all FrameworkModels are destroyed, clean global static resources, shutdown dubbo completely
        destroyGlobalResources();
    }

    private void checkApplicationDestroy() {
        if (applicationModels.size() > 0) {
            List<String> remainApplications = applicationModels.stream()
                .map(model -> model.getDesc())
                .collect(Collectors.toList());
            throw new IllegalStateException("Not all application models are completely destroyed, remaining " +
                remainApplications.size() + " application models may be created during destruction: " + remainApplications);
        }
    }

    private void destroyGlobalResources() {
        synchronized (globalLock) {
            if (allInstances.isEmpty()) {
                GlobalResourcesRepository.getInstance().destroy();
            }
        }
    }

    /**
     * FrameworkModel(框架模型)的默认模型获取工厂方法defaultModel()
     * During destroying the default FrameworkModel, the FrameworkModel.defaultModel() or ApplicationModel.defaultModel()
     * will return a broken model, maybe cause unpredictable problem.
     * Recommendation: Avoid using the default model as much as possible.
     * @return the global default FrameworkModel
     */
    public static FrameworkModel defaultModel() {
        // 双重校验锁的形式创建单例对象
        FrameworkModel instance = defaultInstance;
        if (instance == null) {
            synchronized (globalLock) {
                // 重置默认框架模型
                resetDefaultFrameworkModel();
                if (defaultInstance == null) {
                    defaultInstance = new FrameworkModel();
                }
                instance = defaultInstance;
            }
        }
        Assert.notNull(instance, "Default FrameworkModel is null");
        return instance;
    }

    /**
     * Get all framework model instances
     * @return
     */
    public static List<FrameworkModel> getAllInstances() {
        return Collections.unmodifiableList(new ArrayList<>(allInstances));
    }

    /**
     * Destroy all framework model instances, shutdown dubbo engine completely.
     */
    public static void destroyAll() {
        for (FrameworkModel frameworkModel : new ArrayList<>(allInstances)) {
            frameworkModel.destroy();
        }
    }

    public ApplicationModel newApplication() {
        return new ApplicationModel(this);
    }

    /**
     * Get or create default application model
     * @return
     */
    public ApplicationModel defaultApplication() {
        ApplicationModel appModel = this.defaultAppModel;
        if (appModel == null) {
            // check destroyed before acquire inst lock, avoid blocking during destroying
            checkDestroyed();
            resetDefaultAppModel();
            if ((appModel = this.defaultAppModel) == null) {
                synchronized (instLock) {
                    if (this.defaultAppModel == null) {
                        this.defaultAppModel = newApplication();
                    }
                    appModel = this.defaultAppModel;
                }
            }
        }
        Assert.notNull(appModel, "Default ApplicationModel is null");
        return appModel;
    }

    ApplicationModel getDefaultAppModel() {
        return defaultAppModel;
    }

    /**
     *  FrameworkModel的添加应用程序方法
     */
    void addApplication(ApplicationModel applicationModel) {
        // can not add new application if it's destroying
        // 检查FrameworkModel对象是否已经被标记为销毁状态,如果已经被销毁了则抛出异常无需执行逻辑
        checkDestroyed();
        synchronized (instLock) {
            // 如果还未添加过当前参数传递应用模型
            if (!this.applicationModels.contains(applicationModel)) {
                // 为当前应用模型生成内部id
                applicationModel.setInternalId(buildInternalId(getInternalId(), appIndex.getAndIncrement()));
                // 添加到成员变量集合applicationModels中
                this.applicationModels.add(applicationModel);
                // 如果非内部的则也向公开应用模型集合pubApplicationModels中添加一下
                if (!applicationModel.isInternal()) {
                    this.pubApplicationModels.add(applicationModel);
                }
                resetDefaultAppModel();
            }
        }
    }

    void removeApplication(ApplicationModel model) {
        synchronized (instLock) {
            this.applicationModels.remove(model);
            if (!model.isInternal()) {
                this.pubApplicationModels.remove(model);
            }
            resetDefaultAppModel();
        }
    }

    /**
     * Protocols are special resources that need to be destroyed as soon as possible.
     *
     * Since connections inside protocol are not classified by applications, trying to destroy protocols in advance might only work for singleton application scenario.
     */
    void tryDestroyProtocols() {
        synchronized (instLock) {
            if (pubApplicationModels.size() == 0) {
                notifyProtocolDestroy();
            }
        }
    }

    void tryDestroy() {
        synchronized (instLock) {
            if (pubApplicationModels.size() == 0) {
                destroy();
            }
        }
    }

    private void checkDestroyed() {
        if (isDestroyed()) {
            throw new IllegalStateException("FrameworkModel is destroyed");
        }
    }

    /**
     * 重置默认的应用模型对象
     */
    private void resetDefaultAppModel() {
        synchronized (instLock) {
            if (this.defaultAppModel != null && !this.defaultAppModel.isDestroyed()) {
                return;
            }
            // 取第一个公开的应用模型做为默认应用模型
            ApplicationModel oldDefaultAppModel = this.defaultAppModel;
            if (pubApplicationModels.size() > 0) {
                this.defaultAppModel = pubApplicationModels.get(0);
            } else {
                this.defaultAppModel = null;
            }
            if (defaultInstance == this && oldDefaultAppModel != this.defaultAppModel) {
                if (LOGGER.isInfoEnabled()) {
                    LOGGER.info("Reset global default application from " + safeGetModelDesc(oldDefaultAppModel) + " to " + safeGetModelDesc(this.defaultAppModel));
                }
            }
        }
    }

    private static void resetDefaultFrameworkModel() {
        // 全局悲观锁，同一个时刻只能有一个线程执行重置操作
        synchronized (globalLock) {
            // defaultInstance为当前成员变量FrameworkModel类型代表当前默认的FrameworkModel类型的实例对象
            if (defaultInstance != null && !defaultInstance.isDestroyed()) {
                return;
            }
            FrameworkModel oldDefaultFrameworkModel = defaultInstance;
            // 存在实例模型列表则直接从内存缓存中查后续不需要创建了
            if (allInstances.size() > 0) {
                // 当前存在的有FrameworkModel框架实例多个列表则取第一个为默认的
                defaultInstance = allInstances.get(0);
            } else {
                defaultInstance = null;
            }
            if (oldDefaultFrameworkModel != defaultInstance) {
                if (LOGGER.isInfoEnabled()) {
                    LOGGER.info("Reset global default framework from " + safeGetModelDesc(oldDefaultFrameworkModel) + " to " + safeGetModelDesc(defaultInstance));
                }
            }
        }
    }

    private static String safeGetModelDesc(ScopeModel scopeModel) {
        return scopeModel != null ? scopeModel.getDesc() : null;
    }

    /**
     * Get all application models except for the internal application model.
     */
    public List<ApplicationModel> getApplicationModels() {
        return Collections.unmodifiableList(pubApplicationModels);
    }

    /**
     * Get all application models including the internal application model.
     */
    public List<ApplicationModel> getAllApplicationModels() {
        return Collections.unmodifiableList(applicationModels);
    }

    public ApplicationModel getInternalApplicationModel() {
        return internalApplicationModel;
    }

    public FrameworkServiceRepository getServiceRepository() {
        return serviceRepository;
    }

    @Override
    public Environment getModelEnvironment() {
        throw new UnsupportedOperationException("Environment is inaccessible for FrameworkModel");
    }

    @Override
    protected boolean checkIfClassLoaderCanRemoved(ClassLoader classLoader) {
        return super.checkIfClassLoaderCanRemoved(classLoader) &&
            applicationModels.stream().noneMatch(applicationModel -> applicationModel.containsClassLoader(classLoader));
    }
}
