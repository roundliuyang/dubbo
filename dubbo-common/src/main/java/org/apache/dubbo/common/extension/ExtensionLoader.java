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
package org.apache.dubbo.common.extension;

import org.apache.dubbo.common.Extension;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.beans.support.InstantiationStrategy;
import org.apache.dubbo.common.context.Lifecycle;
import org.apache.dubbo.common.extension.support.ActivateComparator;
import org.apache.dubbo.common.extension.support.WrapperComparator;
import org.apache.dubbo.common.lang.Prioritized;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.resource.Disposable;
import org.apache.dubbo.common.utils.ArrayUtils;
import org.apache.dubbo.common.utils.ClassLoaderResourceLoader;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.ConcurrentHashSet;
import org.apache.dubbo.common.utils.ConfigUtils;
import org.apache.dubbo.common.utils.Holder;
import org.apache.dubbo.common.utils.NativeUtils;
import org.apache.dubbo.common.utils.ReflectUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.apache.dubbo.rpc.model.FrameworkModel;
import org.apache.dubbo.rpc.model.ModuleModel;
import org.apache.dubbo.rpc.model.ScopeModel;
import org.apache.dubbo.rpc.model.ScopeModelAccessor;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import static java.util.Arrays.asList;
import static java.util.ServiceLoader.load;
import static java.util.stream.StreamSupport.stream;
import static org.apache.dubbo.common.constants.CommonConstants.COMMA_SPLIT_PATTERN;
import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.REMOVE_VALUE_PREFIX;

/**
 * {@link org.apache.dubbo.rpc.model.ApplicationModel}, {@code DubboBootstrap} and this class are
 * at present designed to be singleton or static (by itself totally static or uses some static fields).
 * So the instances returned from them are of process or classloader scope. If you want to support
 * multiple dubbo servers in a single process, you may need to refactor these three classes.
 * <p>
 * Load dubbo extensions
 * <ul>
 * <li>auto inject dependency extension </li>
 * <li>auto wrap extension in wrapper </li>
 * <li>default extension is an adaptive instance</li>
 * </ul>
 *
 * @see <a href="http://java.sun.com/j2se/1.5.0/docs/guide/jar/jar.html#Service%20Provider">Service Provider in Java 5</a>
 * @see org.apache.dubbo.common.extension.SPI
 * @see org.apache.dubbo.common.extension.Adaptive
 * @see org.apache.dubbo.common.extension.Activate
 */
public class ExtensionLoader<T> {

    private static final Logger logger = LoggerFactory.getLogger(ExtensionLoader.class);

    private static final Pattern NAME_SEPARATOR = Pattern.compile("\\s*[,]+\\s*");

    private final ConcurrentMap<Class<?>, Object> extensionInstances = new ConcurrentHashMap<>(64);

    private final Class<?> type;

    private final ExtensionInjector injector;

    private final ConcurrentMap<Class<?>, String> cachedNames = new ConcurrentHashMap<>();

    private final Holder<Map<String, Class<?>>> cachedClasses = new Holder<>();

    private final Map<String, Object> cachedActivates = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Map<String, Set<String>> cachedActivateGroups = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Map<String, String[][]> cachedActivateValues = Collections.synchronizedMap(new LinkedHashMap<>());
    private final ConcurrentMap<String, Holder<Object>> cachedInstances = new ConcurrentHashMap<>();
    private final Holder<Object> cachedAdaptiveInstance = new Holder<>();
    private volatile Class<?> cachedAdaptiveClass = null;
    private String cachedDefaultName;
    private volatile Throwable createAdaptiveInstanceError;

    private Set<Class<?>> cachedWrapperClasses;

    private Map<String, IllegalStateException> exceptions = new ConcurrentHashMap<>();

    private static volatile LoadingStrategy[] strategies = loadLoadingStrategies();

    /**
     * Record all unacceptable exceptions when using SPI
     */
    private Set<String> unacceptableExceptions = new ConcurrentHashSet<>();
    private ExtensionDirector extensionDirector;
    private List<ExtensionPostProcessor> extensionPostProcessors;
    private InstantiationStrategy instantiationStrategy;
    private ActivateComparator activateComparator;
    private ScopeModel scopeModel;
    private AtomicBoolean destroyed = new AtomicBoolean();

    public static void setLoadingStrategies(LoadingStrategy... strategies) {
        if (ArrayUtils.isNotEmpty(strategies)) {
            ExtensionLoader.strategies = strategies;
        }
    }

    /**
     * Load all {@link Prioritized prioritized} {@link LoadingStrategy Loading Strategies} via {@link ServiceLoader}
     *
     * @return non-null
     * @since 2.7.7
     */
    private static LoadingStrategy[] loadLoadingStrategies() {
        return stream(load(LoadingStrategy.class).spliterator(), false)
            .sorted()
            .toArray(LoadingStrategy[]::new);
    }

    /**
     * Get all {@link LoadingStrategy Loading Strategies}
     *
     * @return non-null
     * @see LoadingStrategy
     * @see Prioritized
     * @since 2.7.7
     */
    public static List<LoadingStrategy> getLoadingStrategies() {
        return asList(strategies);
    }

    ExtensionLoader(Class<?> type, ExtensionDirector extensionDirector, ScopeModel scopeModel) {
        //当前扩展加载器,需要加载的扩展的类型
        this.type = type;
        // 创建扩展加载器的扩展访问器对象
        this.extensionDirector = extensionDirector;
        // 从扩展访问器中获取扩展执行前后的回调器
        this.extensionPostProcessors = extensionDirector.getExtensionPostProcessors();
        // 创建实例化对象的策略对象
        initInstantiationStrategy();
        // 如果当前扩展类型为扩展注入器类型则设置当前注入器变量为空,否则的话获取一个扩展注入器扩展对象
        // 1)这里有个type为空的判断,普通的扩展类型肯定不是ExtensionInjector类型 这里必定会为每个非扩展注入ExtensionInjector类型创建一个ExtensionInjector类型的扩展对象,
        // 2) 这里代码会走extensionDirector.getExtensionLoader(ExtensionInjector.class)这一步进去之后的代码刚刚看过就不再看了,这个代码会创建一个为ExtensionInjector扩展对象的加载器对象ExtensionLoader
        // 3) getAdaptiveExtension() 这个方法就是通过扩展加载器获取具体的扩展对象的方法我们会详细说
        this.injector = (type == ExtensionInjector.class ? null : extensionDirector.getExtensionLoader(ExtensionInjector.class)
            .getAdaptiveExtension());
        // 创建Activate注解的排序器
        this.activateComparator = new ActivateComparator(extensionDirector);
        // 为扩展加载器下的域模型对象赋值
        this.scopeModel = scopeModel;
    }

    private void initInstantiationStrategy() {
        instantiationStrategy = extensionPostProcessors.stream()
            .filter(extensionPostProcessor -> extensionPostProcessor instanceof ScopeModelAccessor)
            .map(extensionPostProcessor -> new InstantiationStrategy((ScopeModelAccessor) extensionPostProcessor))
            .findFirst()
            .orElse(new InstantiationStrategy());
    }

    /**
     * @see ApplicationModel#getExtensionDirector()
     * @see FrameworkModel#getExtensionDirector()
     * @see ModuleModel#getExtensionDirector()
     * @see ExtensionDirector#getExtensionLoader(java.lang.Class)
     * @deprecated get extension loader from extension director of some module.
     */
    @Deprecated
    public static <T> ExtensionLoader<T> getExtensionLoader(Class<T> type) {
        return ApplicationModel.defaultModel().getDefaultModule().getExtensionLoader(type);
    }

    @Deprecated
    public static void resetExtensionLoader(Class type) {
    }

    public void destroy() {
        if (!destroyed.compareAndSet(false, true)) {
            return;
        }
        // destroy raw extension instance
        extensionInstances.forEach((type, instance) -> {
            if (instance instanceof Disposable) {
                Disposable disposable = (Disposable) instance;
                try {
                    disposable.destroy();
                } catch (Exception e) {
                    logger.error("Error destroying extension " + disposable, e);
                }
            }
        });
        extensionInstances.clear();

        // destroy wrapped extension instance
        for (Holder<Object> holder : cachedInstances.values()) {
            Object wrappedInstance = holder.get();
            if (wrappedInstance instanceof Disposable) {
                Disposable disposable = (Disposable) wrappedInstance;
                try {
                    disposable.destroy();
                } catch (Exception e) {
                    logger.error("Error destroying extension " + disposable, e);
                }
            }
        }
        cachedInstances.clear();
    }

    private void checkDestroyed() {
        if (destroyed.get()) {
            throw new IllegalStateException("ExtensionLoader is destroyed: " + type);
        }
    }

    public String getExtensionName(T extensionInstance) {
        return getExtensionName(extensionInstance.getClass());
    }

    public String getExtensionName(Class<?> extensionClass) {
        getExtensionClasses();// load class
        return cachedNames.get(extensionClass);
    }

    /**
     * 获取自动激活扩展
     * This is equivalent to {@code getActivateExtension(url, key, null)}
     *
     * @param url url 服务的url
     * @param key url parameter key which used to get extension point names  用于获取扩展点名称的url参数键 比如监听器:exporter.listener,过滤器:params-filter,telnet处理器:telnet
     * @return extension list which are activated.
     * @see #getActivateExtension(org.apache.dubbo.common.URL, String, String)
     */
    public List<T> getActivateExtension(URL url, String key) {
        return getActivateExtension(url, key, null);
    }

    /**
     * This is equivalent to {@code getActivateExtension(url, values, null)}
     *
     * @param url    url
     * @param values extension point names
     * @return extension list which are activated
     * @see #getActivateExtension(org.apache.dubbo.common.URL, String[], String)
     */
    public List<T> getActivateExtension(URL url, String[] values) {
        return getActivateExtension(url, values, null);
    }

    /**
     * This is equivalent to {@code getActivateExtension(url, url.getParameter(key).split(","), null)}
     *
     * @param url   url 服务的url
     * @param key   url parameter key which used to get extension point names      用于获取扩展点名称的url参数键 比如监听器:exporter.listener,过滤器:params-filter,telnet处理器:telnet
     * @param group group     用于筛选的分组,比如过滤器中使用此参数来区分消费者使用这个过滤器还是提供者使用这个过滤器他们的group参数分表为consumer,provider
     * @return extension list which are activated.
     * @see #getActivateExtension(org.apache.dubbo.common.URL, String[], String)
     */
    public List<T> getActivateExtension(URL url, String key, String group) {
        // 从参数中获取 url 指定的值
        String value = url.getParameter(key);
        // 调用下个重载方法
        return getActivateExtension(url, StringUtils.isEmpty(value) ? null : COMMA_SPLIT_PATTERN.split(value), group);
    }

    /**
     * 获取激活扩展
     * Get activate extensions.
     *
     * @param url      服务的url
     * @param values extension point names   这个value是扩展点的名字 当指定了时候会使用指定的名字的扩展
     * @param group  group    用于筛选的分组,比如过滤器中使用此参数来区分消费者使用这个过滤器还是提供者使用这个过滤器他们的group参数分表为consumer,provider
     * @return extension list which are activated   获取激活扩展.
     * @see org.apache.dubbo.common.extension.Activate
     */
    @SuppressWarnings("deprecation")
    public List<T> getActivateExtension(URL url, String[] values, String group) {
        // 检查扩展加载器是否被销毁了
        checkDestroyed();
        // solve the bug of using @SPI's wrapper method to report a null pointer exception.
        // 创建个有序的Map集合,用来对扩展进行排序
        Map<Class<?>, T> activateExtensionsMap = new TreeMap<>(activateComparator);
        // 初始化扩展名字,指定了扩展名字values不为空
        List<String> names = values == null ? new ArrayList<>(0) : asList(values);
        // 扩展名字使用Set集合进行去重
        Set<String> namesSet = new HashSet<>(names);
        // 参数常量是 -default  扩展名字是否不包含默认的
        if (!namesSet.contains(REMOVE_VALUE_PREFIX + DEFAULT_KEY)) {
            if (cachedActivateGroups.size() == 0) {
                synchronized (cachedActivateGroups) {
                    // cache all extensions
                    if (cachedActivateGroups.size() == 0) {
                        // 加载扩展类型对应的扩展类,这个具体细节参考源码或者《[Dubbo3.0.7源码解析系列]-5-Dubbo的SPI扩展机制与自适应扩展对象的创建与扩展文件的扫描源码解析》章节
                        getExtensionClasses();
                        for (Map.Entry<String, Object> entry : cachedActivates.entrySet()) {
                            String name = entry.getKey();
                            Object activate = entry.getValue();

                            String[] activateGroup, activateValue;

                            // 遍历所有的activates列表获取group()和value()值
                            if (activate instanceof Activate) {
                                activateGroup = ((Activate) activate).group();
                                activateValue = ((Activate) activate).value();
                            } else if (activate instanceof com.alibaba.dubbo.common.extension.Activate) {
                                activateGroup = ((com.alibaba.dubbo.common.extension.Activate) activate).group();
                                activateValue = ((com.alibaba.dubbo.common.extension.Activate) activate).value();
                            } else {
                                continue;
                            }
                            // 缓存分组值
                            cachedActivateGroups.put(name, new HashSet<>(Arrays.asList(activateGroup)));
                            String[][] keyPairs = new String[activateValue.length][];
                            for (int i = 0; i < activateValue.length; i++) {
                                if (activateValue[i].contains(":")) {
                                    keyPairs[i] = new String[2];
                                    String[] arr = activateValue[i].split(":");
                                    keyPairs[i][0] = arr[0];
                                    keyPairs[i][1] = arr[1];
                                } else {
                                    keyPairs[i] = new String[1];
                                    keyPairs[i][0] = activateValue[i];
                                }
                            }
                            // 缓存指定扩展信息
                            cachedActivateValues.put(name, keyPairs);
                        }
                    }
                }
            }

            // traverse all cached extensions
            // 遍历所有激活的扩展名字和扩展分组集合
            cachedActivateGroups.forEach((name, activateGroup) -> {
                //筛选当前扩展的扩展分组与激活扩展的扩展分组是否可以匹配
                if (isMatchGroup(group, activateGroup)
                    //不能是指定的扩展名字
                    && !namesSet.contains(name)
                    //也不能是带有 -指定扩展名字
                    && !namesSet.contains(REMOVE_VALUE_PREFIX + name)
                    //如果在Active注解中配置了value则当指定的键出现在URL的参数中时，激活当前扩展名。
                    //如果未配置value属性则默认都是匹配的(cachedActivateValues中不存在对应扩展名字的缓存的时候默认为true)
                    && isActive(cachedActivateValues.get(name), url)) {

                    //缓存激活的扩展类型映射的扩展名字
                    activateExtensionsMap.put(getExtensionClass(name), getExtension(name));
                }
            });
        }

        if (namesSet.contains(DEFAULT_KEY)) {
            // will affect order
            // `ext1,default,ext2` means ext1 will happens before all of the default extensions while ext2 will after them
            ArrayList<T> extensionsResult = new ArrayList<>(activateExtensionsMap.size() + names.size());
            for (String name : names) {
                if (name.startsWith(REMOVE_VALUE_PREFIX)
                    || namesSet.contains(REMOVE_VALUE_PREFIX + name)) {
                    continue;
                }
                if (DEFAULT_KEY.equals(name)) {
                    extensionsResult.addAll(activateExtensionsMap.values());
                    continue;
                }
                if (containsExtension(name)) {
                    extensionsResult.add(getExtension(name));
                }
            }
            return extensionsResult;
        } else {
            // add extensions, will be sorted by its order
            for (String name : names) {
                if (name.startsWith(REMOVE_VALUE_PREFIX)
                    || namesSet.contains(REMOVE_VALUE_PREFIX + name)) {
                    continue;
                }
                if (DEFAULT_KEY.equals(name)) {
                    continue;
                }
                if (containsExtension(name)) {
                    activateExtensionsMap.put(getExtensionClass(name), getExtension(name));
                }
            }
            return new ArrayList<>(activateExtensionsMap.values());
        }
    }

    public List<T> getActivateExtensions() {
        checkDestroyed();
        List<T> activateExtensions = new ArrayList<>();
        TreeMap<Class<?>, T> activateExtensionsMap = new TreeMap<>(activateComparator);
        getExtensionClasses();
        for (Map.Entry<String, Object> entry : cachedActivates.entrySet()) {
            String name = entry.getKey();
            Object activate = entry.getValue();
            if (!(activate instanceof Activate)) {
                continue;
            }
            activateExtensionsMap.put(getExtensionClass(name), getExtension(name));
        }
        if (!activateExtensionsMap.isEmpty()) {
            activateExtensions.addAll(activateExtensionsMap.values());
        }

        return activateExtensions;
    }

    private boolean isMatchGroup(String group, Set<String> groups) {
        if (StringUtils.isEmpty(group)) {
            return true;
        }
        if (CollectionUtils.isNotEmpty(groups)) {
            return groups.contains(group);
        }
        return false;
    }

    private boolean isActive(String[][] keyPairs, URL url) {
        if (keyPairs.length == 0) {
            return true;
        }
        for (String[] keyPair : keyPairs) {
            // @Active(value="key1:value1, key2:value2")
            String key;
            String keyValue = null;
            if (keyPair.length > 1) {
                key = keyPair[0];
                keyValue = keyPair[1];
            } else {
                key = keyPair[0];
            }

            String realValue = url.getParameter(key);
            if (StringUtils.isEmpty(realValue)) {
                realValue = url.getAnyMethodParameter(key);
            }
            if ((keyValue != null && keyValue.equals(realValue)) || (keyValue == null && ConfigUtils.isNotEmpty(realValue))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get extension's instance. Return <code>null</code> if extension is not found or is not initialized. Pls. note
     * that this method will not trigger extension load.
     * <p>
     * In order to trigger extension load, call {@link #getExtension(String)} instead.
     *
     * @see #getExtension(String)
     */
    @SuppressWarnings("unchecked")
    public T getLoadedExtension(String name) {
        checkDestroyed();
        if (StringUtils.isEmpty(name)) {
            throw new IllegalArgumentException("Extension name == null");
        }
        Holder<Object> holder = getOrCreateHolder(name);
        return (T) holder.get();
    }

    private Holder<Object> getOrCreateHolder(String name) {
        Holder<Object> holder = cachedInstances.get(name);
        if (holder == null) {
            cachedInstances.putIfAbsent(name, new Holder<>());
            holder = cachedInstances.get(name);
        }
        return holder;
    }

    /**
     * Return the list of extensions which are already loaded.
     * <p>
     * Usually {@link #getSupportedExtensions()} should be called in order to get all extensions.
     *
     * @see #getSupportedExtensions()
     */
    public Set<String> getLoadedExtensions() {
        return Collections.unmodifiableSet(new TreeSet<>(cachedInstances.keySet()));
    }

    @SuppressWarnings("unchecked")
    public List<T> getLoadedExtensionInstances() {
        checkDestroyed();
        List<T> instances = new ArrayList<>();
        cachedInstances.values().forEach(holder -> instances.add((T) holder.get()));
        return instances;
    }

    /**
     * 这里我们要分析的是ExtensionLoader类型的getExtension(String name)方法, 有了前面自适应扩展的铺垫,
     * 这里就更容易来看了getExtension是根据扩展名字获取具体扩展的通用方法,我们来根据某个类型来获取扩展的时候就是走的这里,比如在这个博客开头的介绍:
     * ApplicationModel中获取配置管理器对象
     * configManager = (ConfigManager) this.getExtensionLoader(ApplicationExt.class).getExtension(ConfigManager.NAME);

     * Find the extension with the given name. If the specified name is not found, then {@link IllegalStateException}
     * will be thrown.
     */
    public T getExtension(String name) {
        // 这里并不能看到什么,只多传了个参数wrap为true调用另外一个重载的方法
        T extension = getExtension(name, true);
        if (extension == null) {
            throw new IllegalArgumentException("Not find extension: " + name);
        }
        return extension;
    }

    @SuppressWarnings("unchecked")
    public T getExtension(String name, boolean wrap) {
        // 检查扩展加载器是否已被销毁
        checkDestroyed();
        if (StringUtils.isEmpty(name)) {
            throw new IllegalArgumentException("Extension name == null");
        }
        // 扩展名字为true则加载默认扩展
        if ("true".equals(name)) {
            return getDefaultExtension();
        }
        // 非wrap类型则将缓存的扩展名字key加上_origin后缀
        // wrap是aop机制 俗称切面,这个origin在aop里面可以称为切点,下面的wrap扩展可以称为增强通知的类型,普通扩展和wrap扩展的扩展名字是一样的
        String cacheKey = name;
        if (!wrap) {
            cacheKey += "_origin";
        }
        // 从cachedInstances缓存中查询
        final Holder<Object> holder = getOrCreateHolder(cacheKey);
        Object instance = holder.get();
        // 缓存中不存在则创建扩展对象 双重校验锁
        if (instance == null) {
            synchronized (holder) {
                // 双重校验锁的方式
                instance = holder.get();
                if (instance == null) {
                    // 创建扩展对象
                    instance = createExtension(name, wrap);
                    holder.set(instance);
                }
            }
        }
        return (T) instance;
    }

    /**
     * Get the extension by specified name if found, or {@link #getDefaultExtension() returns the default one}
     *
     * @param name the name of extension
     * @return non-null
     */
    public T getOrDefaultExtension(String name) {
        return containsExtension(name) ? getExtension(name) : getDefaultExtension();
    }

    /**
     * Return default extension, return <code>null</code> if it's not configured.
     */
    public T getDefaultExtension() {
        // 加载扩展类型对应的所有扩展SPI实现类型,在加载所有扩展实现类型的时候会缓存这个扩展的默认实现类型,将对象缓存在cachedDefaultName中
        getExtensionClasses();
        if (StringUtils.isBlank(cachedDefaultName) || "true".equals(cachedDefaultName)) {
            return null;
        }
        // 再回到加载扩展的方法
        return getExtension(cachedDefaultName);
    }

    public boolean hasExtension(String name) {
        checkDestroyed();
        if (StringUtils.isEmpty(name)) {
            throw new IllegalArgumentException("Extension name == null");
        }
        Class<?> c = this.getExtensionClass(name);
        return c != null;
    }

    public Set<String> getSupportedExtensions() {
        checkDestroyed();
        Map<String, Class<?>> classes = getExtensionClasses();
        return Collections.unmodifiableSet(new TreeSet<>(classes.keySet()));
    }

    public Set<T> getSupportedExtensionInstances() {
        checkDestroyed();
        List<T> instances = new LinkedList<>();
        Set<String> supportedExtensions = getSupportedExtensions();
        if (CollectionUtils.isNotEmpty(supportedExtensions)) {
            for (String name : supportedExtensions) {
                instances.add(getExtension(name));
            }
        }
        // sort the Prioritized instances
        instances.sort(Prioritized.COMPARATOR);
        return new LinkedHashSet<>(instances);
    }

    /**
     * Return default extension name, return <code>null</code> if not configured.
     */
    public String getDefaultExtensionName() {
        getExtensionClasses();
        return cachedDefaultName;
    }

    /**
     * Register new extension via API
     *
     * @param name  extension name
     * @param clazz extension class
     * @throws IllegalStateException when extension with the same name has already been registered.
     */
    public void addExtension(String name, Class<?> clazz) {
        checkDestroyed();
        getExtensionClasses(); // load classes

        if (!type.isAssignableFrom(clazz)) {
            throw new IllegalStateException("Input type " +
                clazz + " doesn't implement the Extension " + type);
        }
        if (clazz.isInterface()) {
            throw new IllegalStateException("Input type " +
                clazz + " can't be interface!");
        }

        if (!clazz.isAnnotationPresent(Adaptive.class)) {
            if (StringUtils.isBlank(name)) {
                throw new IllegalStateException("Extension name is blank (Extension " + type + ")!");
            }
            if (cachedClasses.get().containsKey(name)) {
                throw new IllegalStateException("Extension name " +
                    name + " already exists (Extension " + type + ")!");
            }

            cachedNames.put(clazz, name);
            cachedClasses.get().put(name, clazz);
        } else {
            if (cachedAdaptiveClass != null) {
                throw new IllegalStateException("Adaptive Extension already exists (Extension " + type + ")!");
            }

            cachedAdaptiveClass = clazz;
        }
    }

    /**
     * Replace the existing extension via API
     *
     * @param name  extension name
     * @param clazz extension class
     * @throws IllegalStateException when extension to be placed doesn't exist
     * @deprecated not recommended any longer, and use only when test
     */
    @Deprecated
    public void replaceExtension(String name, Class<?> clazz) {
        checkDestroyed();
        getExtensionClasses(); // load classes

        if (!type.isAssignableFrom(clazz)) {
            throw new IllegalStateException("Input type " +
                clazz + " doesn't implement Extension " + type);
        }
        if (clazz.isInterface()) {
            throw new IllegalStateException("Input type " +
                clazz + " can't be interface!");
        }

        if (!clazz.isAnnotationPresent(Adaptive.class)) {
            if (StringUtils.isBlank(name)) {
                throw new IllegalStateException("Extension name is blank (Extension " + type + ")!");
            }
            if (!cachedClasses.get().containsKey(name)) {
                throw new IllegalStateException("Extension name " +
                    name + " doesn't exist (Extension " + type + ")!");
            }

            cachedNames.put(clazz, name);
            cachedClasses.get().put(name, clazz);
            cachedInstances.remove(name);
        } else {
            if (cachedAdaptiveClass == null) {
                throw new IllegalStateException("Adaptive Extension doesn't exist (Extension " + type + ")!");
            }

            cachedAdaptiveClass = clazz;
            cachedAdaptiveInstance.set(null);
        }
    }

    /*
    ExtensionLoader中的getAdaptiveExtension()方法,这个方法也是我们看到的第一个获取扩展对象的方法 ,
    这个方法可以帮助我们通过SPI机制从扩展文件中找到需要的扩展类型并创建它的对象,
    自适应扩展:如果对设计模式比较了解的可能会联想到适配器模式,自适应扩展其实就是适配器模式的思路,自适应扩展有两种策略:

    一种是我们自己实现自适应扩展:然后使用@Adaptive修饰这个时候适配器的逻辑由我们自己实现,当扩展加载器去查找具体的扩展的时候可以通过找到我们这个对应的适配器扩展,
    然后适配器扩展帮忙去查询真正的扩展,这个比如我们下面要举的扩展注入器的例子,具体扩展通过扩展注入器适配器,注入器适配器来查询具体的注入器扩展实现来帮忙查找扩展。

    还有一种方式是我们未实现这个自适应扩展,Dubbo在运行时通过字节码动态代理的方式在运行时生成一个适配器,使用这个适配器映射到具体的扩展.
    第二种情况往往用在比如 Protocol、Cluster、LoadBalance 等。有时，有些拓展并不想在框架启动阶段被加载，而是希望在拓展方法被调用时，根据运行时参数进行加载。
    (如果还不了解可以考虑看下@Adaptive注解加载方法上面的时候扩展是如何加载的)
     */
    @SuppressWarnings("unchecked")
    public T getAdaptiveExtension() {
        // 检查当前扩展加载器是否已经被销毁
        checkDestroyed();
        // 从自适应扩展缓存中查询扩展对象如果存在就直接返回,这个自适应扩展类型只会有一个扩展实现类型如果是多个的话根据是否可以覆盖参数决定扩展实现类是否可以相互覆盖
        Object instance = cachedAdaptiveInstance.get();
        //这个if判断不太优雅 容易多层嵌套,上面instance不为空就可以直接返回了
        if (instance == null) {
            //创建异常则抛出异常直接返回(多线程场景下可能第一个线程异常了第二个线程进来之后走到这里)
            if (createAdaptiveInstanceError != null) {
                throw new IllegalStateException("Failed to create adaptive instance: " +
                    createAdaptiveInstanceError.toString(),
                    createAdaptiveInstanceError);
            }
            // 加锁排队 (单例模式创建对象的思想 双重校验锁)
            synchronized (cachedAdaptiveInstance) {
                //加锁的时候对象都是空的,进来之后先判断下防止重复创建
                instance = cachedAdaptiveInstance.get();
                //只有第一个进来锁的对象为空开始创建扩展对象
                if (instance == null) {
                    try {
                        //根据SPI机制获取类型,创建对象。前面使用单例思想来调用创建自适应扩展对象的方法,下面就让我们深入探究下创建自适应扩展对象的整个过程createAdaptiveExtension()方法
                        instance = createAdaptiveExtension();
                        // 存入缓存
                        cachedAdaptiveInstance.set(instance);
                    } catch (Throwable t) {
                        createAdaptiveInstanceError = t;
                        throw new IllegalStateException("Failed to create adaptive instance: " + t.toString(), t);
                    }
                }
            }
        }

        return (T) instance;
    }

    private IllegalStateException findException(String name) {
        StringBuilder buf = new StringBuilder("No such extension " + type.getName() + " by name " + name);

        int i = 1;
        for (Map.Entry<String, IllegalStateException> entry : exceptions.entrySet()) {
            if (entry.getKey().toLowerCase().startsWith(name.toLowerCase())) {
                if (i == 1) {
                    buf.append(", possible causes: ");
                }
                buf.append("\r\n(");
                buf.append(i++);
                buf.append(") ");
                buf.append(entry.getKey());
                buf.append(":\r\n");
                buf.append(StringUtils.toString(entry.getValue()));
            }
        }

        if (i == 1) {
            buf.append(", no related exception was found, please check whether related SPI module is missing.");
        }
        return new IllegalStateException(buf.toString());
    }

    @SuppressWarnings("unchecked")
    private T createExtension(String name, boolean wrap) {
        // 扩展的创建的第一步扫描所有jar中的扩展实现,这里扫描完之后获取对应扩展名字的扩展实现类型的Class对象
        Class<?> clazz = getExtensionClasses().get(name);
        if (clazz == null || unacceptableExceptions.contains(name)) {
            // 出现异常了 转换下异常信息 再抛出
            throw findException(name);
        }
        try {
            // 当前扩展对象是否已经创建过了则直接从缓存中获取
            T instance = (T) extensionInstances.get(clazz);
            if (instance == null) {
                // 第一次获取缓存中肯定没有则创建扩展对象然后缓存起来
                // createExtensionInstance 这个是与自适应扩展对象创建对象的不同之处
                extensionInstances.putIfAbsent(clazz, createExtensionInstance(clazz));
                instance = (T) extensionInstances.get(clazz);
                instance = postProcessBeforeInitialization(instance, name);
                // 注入扩展自适应方法,这个方法前面讲自适应扩展时候说了,注入自适应扩展方法的自适应扩展对象
                injectExtension(instance);
                instance = postProcessAfterInitialization(instance, name);
            }

            // 是否开启了wrap
            // Dubbo通过Wrapper实现AOP的方法
            if (wrap) {
                // 这个可以参考下Dubbo扩展的加载
                List<Class<?>> wrapperClassesList = new ArrayList<>();
                // wrap类型排序 这个wrap类型是如何来的呢,在前面扫描扩展类型的时候如果当前扩展类型不是Adaptive注解修饰的,
                // 并且当前类型type有个构造器参数是type自身的也是前面加载扩展类型时候说的装饰器模式 可以参考DubboProtocol的构造器
                if (cachedWrapperClasses != null) {
                    wrapperClassesList.addAll(cachedWrapperClasses);
                    // 根据Wrapper注解的order值来进行排序值越小越在列表的前面
                    wrapperClassesList.sort(WrapperComparator.COMPARATOR);
                    // 反转之后值越大就会在列表的前面
                    Collections.reverse(wrapperClassesList);
                }

                // 从缓存中查到了wrapper扩展则遍历这些wrapp扩展进行筛选
                if (CollectionUtils.isNotEmpty(wrapperClassesList)) {
                    for (Class<?> wrapperClass : wrapperClassesList) {
                        Wrapper wrapper = wrapperClass.getAnnotation(Wrapper.class);
                        // 需要包装的扩展名。当此数组为空时，默认值为匹配
                        // 看下当前扩展是否匹配这个wrap,如何判断呢?
                        // wrapper注解不存在或者matches匹配,或者mismatches不包含当前扩展
                        // 如果匹配到了当前扩展对象是需要进行wrapp的就为当前扩展创建当前wrapper扩展对象进行包装
                        boolean match = (wrapper == null) ||
                            ((ArrayUtils.isEmpty(wrapper.matches()) || ArrayUtils.contains(wrapper.matches(), name)) &&
                                !ArrayUtils.contains(wrapper.mismatches(), name));
                        // 这是扩展类型是匹配wrapp的则开始注入
                        if (match) {
                            // 匹配到了就创建所有的wrapper类型的对象同时构造器参数设置为当前类型
                            instance = injectExtension((T) wrapperClass.getConstructor(type).newInstance(instance));
                            instance = postProcessAfterInitialization(instance, name);
                        }
                    }
                }
            }

            // Warning: After an instance of Lifecycle is wrapped by cachedWrapperClasses, it may not still be Lifecycle instance, this application may not invoke the lifecycle.initialize hook.
            // 初始化扩展,如果当前扩展是Lifecycle类型则调用初始化方法
            initExtension(instance);
            return instance;
        } catch (Throwable t) {
            throw new IllegalStateException("Extension instance (name: " + name + ", class: " +
                type + ") couldn't be instantiated: " + t.getMessage(), t);
        }
    }

    /**
     * 创建扩展对象
     */
    private Object createExtensionInstance(Class<?> type) throws ReflectiveOperationException {
        // 在ExtensionLoader构造器中,有个initInstantiationStrategy()方法中new了一个初始化策略InstantiationStrategy类型对象
        return instantiationStrategy.instantiate(type);
    }

    @SuppressWarnings("unchecked")
    private T postProcessBeforeInitialization(T instance, String name) throws Exception {
        if (extensionPostProcessors != null) {
            for (ExtensionPostProcessor processor : extensionPostProcessors) {
                instance = (T) processor.postProcessBeforeInitialization(instance, name);
            }
        }
        return instance;
    }

    @SuppressWarnings("unchecked")
    private T postProcessAfterInitialization(T instance, String name) throws Exception {
        if (instance instanceof ExtensionAccessorAware) {
            ((ExtensionAccessorAware) instance).setExtensionAccessor(extensionDirector);
        }
        if (extensionPostProcessors != null) {
            for (ExtensionPostProcessor processor : extensionPostProcessors) {
                instance = (T) processor.postProcessAfterInitialization(instance, name);
            }
        }
        return instance;
    }

    private boolean containsExtension(String name) {
        return getExtensionClasses().containsKey(name);
    }

    /**
     * 注入自适应扩展
     */
    private T injectExtension(T instance) {
        // 如果注入器为空则直接返回当前对象
        if (injector == null) {
            return instance;
        }

        try {
            // 获取当前对象的当前类的所有方法
            for (Method method : instance.getClass().getMethods()) {
                // 是否为set方法 不是的话则跳过,在这里合法的set方法满足3个条件:
                // set开头,参数只有一个,public修饰
                if (!isSetter(method)) {
                    continue;
                }
                /**
                 * 方法上面是否有注解DisableInject修饰,这种情况也直接跳过
                 * Check {@link DisableInject} to see if we need auto injection for this property
                 */
                if (method.isAnnotationPresent(DisableInject.class)) {
                    continue;
                }
                //方法的参数如果是原生类型也跳过
                Class<?> pt = method.getParameterTypes()[0];
                if (ReflectUtils.isPrimitives(pt)) {
                    continue;
                }

                try {
                    //获取set方法对应的成员变量如setProtocol 属性为protocol
                    String property = getSetterProperty(method);
                    //根据参数类型如Protocol和属性名字如protocol获取应该注入的对象
                    Object object = injector.getInstance(pt, property);
                    if (object != null) {
                        //执行对应对象和对应参数的这个方法
                        method.invoke(instance, object);
                    }
                } catch (Exception e) {
                    logger.error("Failed to inject via method " + method.getName()
                        + " of interface " + type.getName() + ": " + e.getMessage(), e);
                }
            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
        return instance;
    }

    private void initExtension(T instance) {
        if (instance instanceof Lifecycle) {
            Lifecycle lifecycle = (Lifecycle) instance;
            lifecycle.initialize();
        }
    }

    /**
     * get properties name for setter, for instance: setVersion, return "version"
     * <p>
     * return "", if setter name with length less than 3
     */
    private String getSetterProperty(Method method) {
        return method.getName().length() > 3 ? method.getName().substring(3, 4).toLowerCase() + method.getName().substring(4) : "";
    }

    /**
     * return true if and only if:
     * <p>
     * 1, public
     * <p>
     * 2, name starts with "set"
     * <p>
     * 3, only has one parameter
     */
    private boolean isSetter(Method method) {
        return method.getName().startsWith("set")
            && method.getParameterTypes().length == 1
            && Modifier.isPublic(method.getModifiers());
    }

    private Class<?> getExtensionClass(String name) {
        if (type == null) {
            throw new IllegalArgumentException("Extension type == null");
        }
        if (name == null) {
            throw new IllegalArgumentException("Extension name == null");
        }
        return getExtensionClasses().get(name);
    }


    /**
     * 获取扩展类型的方法
     */
    private Map<String, Class<?>> getExtensionClasses() {
        // 缓存中查询扩展类型是否存在
        Map<String, Class<?>> classes = cachedClasses.get();
        if (classes == null) {
            //单例模式双重校验锁判断
            synchronized (cachedClasses) {
                classes = cachedClasses.get();
                if (classes == null) {
                    //加载扩展类型
                    classes = loadExtensionClasses();
                    //将我们扫描到的扩展类型存入成员变量cachedClasses中
                    cachedClasses.set(classes);
                }
            }
        }
        return classes;
    }

    /**
     * 加载扩展类型的方法
     * synchronized in getExtensionClasses
     */
    @SuppressWarnings("deprecation")
    private Map<String, Class<?>> loadExtensionClasses() {
        // 检查扩展加载器是否被销毁
        checkDestroyed();
        // 缓存默认的扩展名到成员变量cachedDefaultName中
        cacheDefaultExtensionName();

        // 加载到扩展集合
        Map<String, Class<?>> extensionClasses = new HashMap<>();

        //扩展策略,在4.3章节中我们介绍了这个类型的UML与说明
        //LoadingStrategy扩展加载策略,目前有3个扩展加载策略
        //DubboInternalLoadingStrategy:Dubbo内置的扩展加载策略,将加载文件目录为META-INF/dubbo/internal/的扩展
        //DubboLoadingStrategy:Dubbo普通的扩展加载策略,将加载目录为META-INF/dubbo/的扩展
        //ServicesLoadingStrategy:JAVA SPI加载策略 ,将加载目录为META-INF/services/的扩展
        //扩展策略集合对象在什么时候初始化的呢在成员变量初始化的时候就创建了集合对象,这个可以看方法loadLoadingStrategies() 通过Java的 SPI加载策略
        for (LoadingStrategy strategy : strategies) {
            // 根据策略从指定文件目录中加载扩展类型
            loadDirectory(extensionClasses, strategy, type.getName());

            // compatible with old ExtensionFactory
            // 如果当前要加载的扩展类型是扩展注入类型则扫描下ExtensionFactory类型的扩展
            if (this.type == ExtensionInjector.class) {
                // 这个方法和上面那个方法是一样的就不详细说了 扫描文件 找到扩展类型
                loadDirectory(extensionClasses, strategy, ExtensionFactory.class.getName());
            }
        }

        //通过loadDirectory扫描 扫描到了ExtensionInjector类型的扩展实现类有3个 我们将会得到这样一个集合例子:
        // "spring" ->  "class org.apache.dubbo.config.spring.extension.SpringExtensionInjector"
        // "scopeBean" ->  "class org.apache.dubbo.common.beans.ScopeBeanExtensionInjector"
        // "spi" ->  "class org.apache.dubbo.common.extension.inject.SpiExtensionInjector"
        return extensionClasses;
    }

    private void loadDirectory(Map<String, Class<?>> extensionClasses, LoadingStrategy strategy, String type) {
        // 加载并根据策略的参数来加载
        loadDirectory(extensionClasses, strategy.directory(), type, strategy.preferExtensionClassLoader(),
            strategy.overridden(), strategy.includedPackages(), strategy.excludedPackages(), strategy.onlyExtensionClassLoaderPackages());
        // 下面两行就是要兼容alibaba的扩展包了
        String oldType = type.replace("org.apache", "com.alibaba");
        loadDirectory(extensionClasses, strategy.directory(), oldType, strategy.preferExtensionClassLoader(),
            strategy.overridden(), strategy.includedPackagesInCompatibleType(), strategy.excludedPackages(), strategy.onlyExtensionClassLoaderPackages());
    }

    /**
     * extract and cache default extension name if exists
     */
    private void cacheDefaultExtensionName() {
        final SPI defaultAnnotation = type.getAnnotation(SPI.class);
        if (defaultAnnotation == null) {
            return;
        }

        String value = defaultAnnotation.value();
        if ((value = value.trim()).length() > 0) {
            String[] names = NAME_SEPARATOR.split(value);
            if (names.length > 1) {
                throw new IllegalStateException("More than 1 default extension name on extension " + type.getName()
                    + ": " + Arrays.toString(names));
            }
            if (names.length == 1) {
                cachedDefaultName = names[0];
            }
        }
    }

    /**
     * 不同的扩展策略传递了不同的参数,但是扩展的加载流程是相同的,这里我们可以参考上面表格
     * @param type 这里我们参考的示例这个值为org.apache.dubbo.common.extension.ExtensionInjector
     */
    private void loadDirectory(Map<String, Class<?>> extensionClasses, String dir, String type,
                               boolean extensionLoaderClassLoaderFirst, boolean overridden, String[] includedPackages,
                               String[] excludedPackages, String[] onlyExtensionClassLoaderPackages) {
        // 扩展目录 + 扩展类型全路径 比如: META-INF/dubbo/internal/org.apache.dubbo.common.extension.ExtensionInjector
        String fileName = dir + type;
        try {
            List<ClassLoader> classLoadersToLoad = new LinkedList<>();

            // try to load from ExtensionLoader's ClassLoader first
            // 是否优先使用扩展加载器的 类加载器
            if (extensionLoaderClassLoaderFirst) {
                ClassLoader extensionLoaderClassLoader = ExtensionLoader.class.getClassLoader();
                if (ClassLoader.getSystemClassLoader() != extensionLoaderClassLoader) {
                    classLoadersToLoad.add(extensionLoaderClassLoader);
                }
            }

            // load from scope model
            // 获取域模型对象的类型加载器 ,这个域模型对象在初始化的时候会将自己的类加载器放入集合中可以参考《3.2.2 初始化ScopeModel》章节
            Set<ClassLoader> classLoaders = scopeModel.getClassLoaders();

            // 没有可用的类加载器则从使用
            if (CollectionUtils.isEmpty(classLoaders)) {
                // 从用于加载类的搜索路径中查找指定名称的所有资源
                Enumeration<java.net.URL> resources = ClassLoader.getSystemResources(fileName);
                if (resources != null) {
                    while (resources.hasMoreElements()) {
                        loadResource(extensionClasses, null, resources.nextElement(), overridden, includedPackages, excludedPackages, onlyExtensionClassLoaderPackages);
                    }
                }
            } else {
                classLoadersToLoad.addAll(classLoaders);
            }
            // 使用类加载资源加载器(ClassLoaderResourceLoader)来加载具体的资源
            Map<ClassLoader, Set<java.net.URL>> resources = ClassLoaderResourceLoader.loadResources(fileName, classLoadersToLoad);
            // 遍历从所有资源文件中读取到资源url地址,key为类加载器,值为扩展文件url如夏所示
            // jar:file:/Users/song/.m2/repository/org/apache/dubbo/dubbo/3.0.7/dubbo-3.0.7.jar!/META-INF/dubbo/internal/org.apache.dubbo.common.extension.ExtensionInjecto
            resources.forEach(((classLoader, urls) ->
                // 从文件中加载完资源之后开始根据类加载器和url加载具体的扩展类型,最后将扩展存放进extensionClasses集合
                loadFromClass(extensionClasses, overridden, urls, classLoader, includedPackages, excludedPackages, onlyExtensionClassLoaderPackages)));
        } catch (Throwable t) {
            logger.error("Exception occurred when loading extension class (interface: " +
                type + ", description file: " + fileName + ").", t);
        }
    }

    /**
     * 使用找到的扩展资源url加载具体扩展类型到内存
     */
    private void loadFromClass(Map<String, Class<?>> extensionClasses, boolean overridden, Set<java.net.URL> urls, ClassLoader classLoader,
                               String[] includedPackages, String[] excludedPackages, String[] onlyExtensionClassLoaderPackages) {
        if (CollectionUtils.isNotEmpty(urls)) {
            // 遍历url 开始加载扩展类型
            for (java.net.URL url : urls) {
                // 使用IO流读取扩展文件的内容
                loadResource(extensionClasses, classLoader, url, overridden, includedPackages, excludedPackages, onlyExtensionClassLoaderPackages);
            }
        }
    }

    private void loadResource(Map<String, Class<?>> extensionClasses, ClassLoader classLoader,
                              java.net.URL resourceURL, boolean overridden, String[] includedPackages, String[] excludedPackages, String[] onlyExtensionClassLoaderPackages) {
        try {
            // 这里固定了文件的格式为utf8
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(resourceURL.openStream(), StandardCharsets.UTF_8))) {
                String line;
                String clazz;
                // 按行读取 例如读取到的内容:spring=org.apache.dubbo.config.spring.extension.SpringExtensionInjector
                while ((line = reader.readLine()) != null) {
                    // 不知道为何会有这么一行代码删除#之后的字符串
                    final int ci = line.indexOf('#');
                    if (ci >= 0) {
                        line = line.substring(0, ci);
                    }
                    line = line.trim();
                    if (line.length() > 0) {
                        try {
                            String name = null;
                            // 扩展文件可能如上面我贴的那样 名字和类型等号隔开,也可能是无类型的,例如扩展加载策略使用的是JDK自带的方式services内容中只包含具体的扩展类型
                            int i = line.indexOf('=');
                            if (i > 0) {
                                name = line.substring(0, i).trim();
                                clazz = line.substring(i + 1).trim();
                            } else {
                                clazz = line;
                            }
                            // isExcluded是否为加载策略要排除的配置,参数这里为空代表全部类型不排除
                            // isIncluded是否为加载策略包含的类型,参数这里为空代表全部文件皆可包含
                            // onlyExtensionClassLoaderPackages参数是否只有扩展类的类加载器可以加载扩展,其他扩展类型的类加载器不能加载扩展 这里结果为false 不排除任何类加载器
                            if (StringUtils.isNotEmpty(clazz) && !isExcluded(clazz, excludedPackages) && isIncluded(clazz, includedPackages)
                                && !isExcludedByClassLoader(clazz, classLoader, onlyExtensionClassLoaderPackages)) {
                                // 根据类全路径加载类到内存
                                loadClass(extensionClasses, resourceURL, Class.forName(clazz, true, classLoader), name, overridden);
                            }
                        } catch (Throwable t) {
                            IllegalStateException e = new IllegalStateException("Failed to load extension class (interface: " + type +
                                ", class line: " + line + ") in " + resourceURL + ", cause: " + t.getMessage(), t);
                            exceptions.put(line, e);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            logger.error("Exception occurred when loading extension class (interface: " +
                type + ", class file: " + resourceURL + ") in " + resourceURL, t);
        }
    }

    private boolean isIncluded(String className, String... includedPackages) {
        if (includedPackages != null && includedPackages.length > 0) {
            for (String includedPackage : includedPackages) {
                if (className.startsWith(includedPackage + ".")) {
                    // one match, return true
                    return true;
                }
            }
            // none matcher match, return false
            return false;
        }
        // matcher is empty, return true
        return true;
    }

    private boolean isExcluded(String className, String... excludedPackages) {
        if (excludedPackages != null) {
            for (String excludePackage : excludedPackages) {
                if (className.startsWith(excludePackage + ".")) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isExcludedByClassLoader(String className, ClassLoader classLoader, String... onlyExtensionClassLoaderPackages) {
        if (onlyExtensionClassLoaderPackages != null) {
            for (String excludePackage : onlyExtensionClassLoaderPackages) {
                if (className.startsWith(excludePackage + ".")) {
                    // if target classLoader is not ExtensionLoader's classLoader should be excluded
                    return !Objects.equals(ExtensionLoader.class.getClassLoader(), classLoader);
                }
            }
        }
        return false;
    }

    private void loadClass(Map<String, Class<?>> extensionClasses, java.net.URL resourceURL, Class<?> clazz, String name,
                           boolean overridden) {
        // 当前clazz是否为type的子类型
        // 这里第一次访问到的type是ExtensionInjector,clazz是SpringExtensionInjector 父子类型关系满足情况
        if (!type.isAssignableFrom(clazz)) {
            throw new IllegalStateException("Error occurred when loading extension class (interface: " +
                type + ", class line: " + clazz.getName() + "), class "
                + clazz.getName() + " is not subtype of interface.");
        }
        // 扩展子类型是否存在这个注解@Adaptive
        if (clazz.isAnnotationPresent(Adaptive.class)) {
            cacheAdaptiveClass(clazz, overridden);
        } else if (isWrapperClass(clazz)) {
            // 扩展子类型构造器中是否有这个类型的接口 (这个可以想象下我们了解的Java IO流中的类型使用到的装饰器模式 构造器传个类型)
            cacheWrapperClass(clazz);
        } else {
            // 无自适应注解,也没有构造器是扩展类型参数 ,这个name我们在扩展文件中找到了就是等号前面那个
            if (StringUtils.isEmpty(name)) {
                // 低版本中可以使用@Extension 扩展注解来标注扩展类型,这里获取注解有两个渠道:
                // 先查询@Extension注解是否存在如果存在则取value值,如果不存在@Extension注解则获取当前类型的名字
                name = findAnnotationName(clazz);
                if (name.length() == 0) {
                    throw new IllegalStateException("No such extension name for the class " + clazz.getName() + " in the config " + resourceURL);
                }
            }

            // 获取扩展名字数组,扩展名字可能为逗号隔开的
            String[] names = NAME_SEPARATOR.split(name);
            if (ArrayUtils.isNotEmpty(names)) {
                // @Activate注解修饰的扩展
                cacheActivateClass(clazz, names[0]);
                for (String n : names) {
                    // cachedNames缓存集合缓存当前扩展类型的扩展名字
                    cacheName(clazz, n);
                    // 将扩展类型加入结果集合extensionClasses中,不允许覆盖的话出现同同名字扩展将抛出异常
                    saveInExtensionClass(extensionClasses, clazz, n, overridden);
                }
            }
        }
    }

    /**
     * cache name
     */
    private void cacheName(Class<?> clazz, String name) {
        if (!cachedNames.containsKey(clazz)) {
            cachedNames.put(clazz, name);
        }
    }

    /**
     * put clazz in extensionClasses
     */
    private void saveInExtensionClass(Map<String, Class<?>> extensionClasses, Class<?> clazz, String name, boolean overridden) {
        Class<?> c = extensionClasses.get(name);
        if (c == null || overridden) {
            // 上面扩展对象加载了这么多最终的目的就是将这个扩展类型存放进结果集合中
            extensionClasses.put(name, clazz);
        } else if (c != clazz) {
            // duplicate implementation is unacceptable
            unacceptableExceptions.add(name);
            String duplicateMsg = "Duplicate extension " + type.getName() + " name " + name + " on " + c.getName() + " and " + clazz.getName();
            logger.error(duplicateMsg);
            throw new IllegalStateException(duplicateMsg);
        }
    }

    /**
     * cache Activate class which is annotated with <code>Activate</code>
     * <p>
     * for compatibility, also cache class with old alibaba Activate annotation
     */
    @SuppressWarnings("deprecation")
    private void cacheActivateClass(Class<?> clazz, String name) {
        Activate activate = clazz.getAnnotation(Activate.class);
        if (activate != null) {
            cachedActivates.put(name, activate);
        } else {
            // support com.alibaba.dubbo.common.extension.Activate
            com.alibaba.dubbo.common.extension.Activate oldActivate = clazz.getAnnotation(com.alibaba.dubbo.common.extension.Activate.class);
            if (oldActivate != null) {
                cachedActivates.put(name, oldActivate);
            }
        }
    }

    /**
     * 即扩展类的自适应机制
     * cache Adaptive class which is annotated with <code>Adaptive</code>
     */
    private void cacheAdaptiveClass(Class<?> clazz, boolean overridden) {
        if (cachedAdaptiveClass == null || overridden) {
            // 成员变量存储这个自适应扩展类型
            cachedAdaptiveClass = clazz;
        } else if (!cachedAdaptiveClass.equals(clazz)) {
            throw new IllegalStateException("More than 1 adaptive class found: "
                + cachedAdaptiveClass.getName()
                + ", " + clazz.getName());
        }
    }

    /**
     * cache wrapper class
     * <p>
     * like: ProtocolFilterWrapper, ProtocolListenerWrapper
     */
    private void cacheWrapperClass(Class<?> clazz) {
        if (cachedWrapperClasses == null) {
            cachedWrapperClasses = new ConcurrentHashSet<>();
        }
        //缓存这个Wrapper类型的扩展
        cachedWrapperClasses.add(clazz);
    }

    /**
     * test if clazz is a wrapper class
     * <p>
     * which has Constructor with given class type as its only argument
     */
    private boolean isWrapperClass(Class<?> clazz) {
        try {
            clazz.getConstructor(type);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private String findAnnotationName(Class<?> clazz) {
        Extension extension = clazz.getAnnotation(Extension.class);
        if (extension != null) {
            return extension.value();
        }

        String name = clazz.getSimpleName();
        if (name.endsWith(type.getSimpleName())) {
            name = name.substring(0, name.length() - type.getSimpleName().length());
        }
        return name.toLowerCase();
    }

    /**
     * 这个方法包含了扩展对象创建初始化的整个生命周期
     */
    @SuppressWarnings("unchecked")
    private T createAdaptiveExtension() {
        try {
            // 获取扩展类型实现类, 创建扩展对象
            T instance = (T) getAdaptiveExtensionClass().newInstance();
            // 注入扩展对象之前的回调方法
            instance = postProcessBeforeInitialization(instance, null);
            // 注入扩展对象
            injectExtension(instance);
            // 注入扩展对象之后的回调方法
            instance = postProcessAfterInitialization(instance, null);
            // 初始化扩展对象的属性,如果当前扩展实例的类型实现了Lifecycle则调用当前扩展对象的生命周期回调方法initialize()(来自Lifecycle接口)
            // 参考样例第一个instance为ExtensionInjector的自适应扩展对象类型为AdaptiveExtensionInjector,自适应扩展注入器(适配器)用来查询具体支持的扩展注入器比如scope,spi,spring注入器
            initExtension(instance);
            return instance;
        } catch (Exception e) {
            throw new IllegalStateException("Can't create adaptive extension " + type + ", cause: " + e.getMessage(), e);
        }
    }


    /**
     * SPI机制获取扩展对象实现类型
     */
    private Class<?> getAdaptiveExtensionClass() {
        //获取扩展类型,将扩展类型存入成员变量cachedClasses中进行缓存
        getExtensionClasses();
        // 在上个方法的详细解析中的最后一步loadClass方法中如果扩展类型存在Adaptive注解将会将扩展类型赋值给cachedAdaptiveClass,
        // 否则的话会把扩展类型都缓存起来存储在扩展集合extensionClasses中
        if (cachedAdaptiveClass != null) {
            return cachedAdaptiveClass;
        }
        //扩展实现类型没有一个这个自适应注解Adaptive时候会走到这里
        //刚刚我们扫描到了扩展类型然后将其存入cachedClasses集合中了 接下来我们看下如何创建扩展类型
        return cachedAdaptiveClass = createAdaptiveExtensionClass();
    }

    /**
     * 自动生成自适应拓展的代码实现，并编译后返回该类
     */
    private Class<?> createAdaptiveExtensionClass() {
        // Adaptive Classes' ClassLoader should be the same with Real SPI interface classes' ClassLoader
        // 获取加载器
        ClassLoader classLoader = type.getClassLoader();
        try {
            // native配置 是否为本地镜像(可以参考官方文档:https://dubbo.apache.org/zh-cn/docs/references/graalvm/support-graalv
            if (NativeUtils.isNative()) {
                return classLoader.loadClass(type.getName() + "$Adaptive");
            }
        } catch (Throwable ignore) {

        }
        //创建一个代码生成器,来生成代码 详细内容我们就下一章来看
        String code = new AdaptiveClassCodeGenerator(type, cachedDefaultName).generate();
        // 获取编译器
        org.apache.dubbo.common.compiler.Compiler compiler = extensionDirector.getExtensionLoader(
            org.apache.dubbo.common.compiler.Compiler.class).getAdaptiveExtension();
        // 生成的代码进行编译
        return compiler.compile(type, code, classLoader);
    }

    @Override
    public String toString() {
        return this.getClass().getName() + "[" + type.getName() + "]";
    }

}
