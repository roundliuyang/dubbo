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
package org.apache.dubbo.common.utils;

import org.apache.dubbo.common.resource.GlobalResourcesRepository;

import java.io.IOException;
import java.lang.ref.SoftReference;
import java.lang.reflect.Field;
import java.net.URL;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

public class ClassLoaderResourceLoader {

    private static SoftReference<Map<ClassLoader, Map<String, Set<URL>>>> classLoaderResourcesCache = null;

    static {
        // register resources destroy listener
        GlobalResourcesRepository.registerGlobalDisposable(()-> destroy());
    }

    /**
     * 借助类加载器的getResources方法遍历所有文件进行扩展文件的查询
     */
    public static Map<ClassLoader, Set<URL>> loadResources(String fileName, List<ClassLoader> classLoaders) {
        Map<ClassLoader, Set<URL>> resources = new ConcurrentHashMap<>();
        // 不同的类加载器之间使用不同的线程异步的方式进行扫描
        CountDownLatch countDownLatch = new CountDownLatch(classLoaders.size());
        for (ClassLoader classLoader : classLoaders) {
            // 多线程扫描,这个是个newCachedThreadPool的类型的线程池
            GlobalResourcesRepository.getGlobalExecutorService().submit(() -> {
                resources.put(classLoader, loadResources(fileName, classLoader));
                countDownLatch.countDown();
            });
        }
        try {
            countDownLatch.await();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(resources));
    }

    public static Set<URL> loadResources(String fileName, ClassLoader currentClassLoader) {
        Map<ClassLoader, Map<String, Set<URL>>> classLoaderCache;
        // 第一次进来类加载器资源缓存是空的
        if (classLoaderResourcesCache == null || (classLoaderCache = classLoaderResourcesCache.get()) == null) {
            synchronized (ClassLoaderResourceLoader.class) {
                if (classLoaderResourcesCache == null || (classLoaderCache = classLoaderResourcesCache.get()) == null) {
                    classLoaderCache = new ConcurrentHashMap<>();
                    // 创建一个类资源映射url的软引用缓存对象
                    // 软引用(soft references)，用于帮助垃圾收集器管理内存使用和消除潜在的内存泄漏。当内存快要不足的时候，GC会迅速的把所有的软引用清除掉，释放内存空间
                    classLoaderResourcesCache = new SoftReference<>(classLoaderCache);
                }
            }
        }
        // 第一次进来时候类加载器url映射缓存是空的,给类加载器缓存对象新增一个值,key是类加载器,值是map类型用来存储文件名对应的url集合
        if (!classLoaderCache.containsKey(currentClassLoader)) {
            classLoaderCache.putIfAbsent(currentClassLoader, new ConcurrentHashMap<>());
        }
        Map<String, Set<URL>> urlCache = classLoaderCache.get(currentClassLoader);
        // 缓存中没有就从文件里面找
        if (!urlCache.containsKey(fileName)) {
            Set<URL> set = new LinkedHashSet<>();
            Enumeration<URL> urls;
            try {
                // getResources这个方法是这样的:加载当前类加载器以及父类加载器所在路径的资源文件,将遇到的所有资源文件全部返回！这个可以理解为使用双亲委派模型中的类加载器 加载各个位置的资源文
                urls = currentClassLoader.getResources(fileName);
                // native配置 是否为本地镜像(k可以参考官方文档:https://dubbo.apache.org/zh-cn/docs/references/graalvm/support-graalvm/
                boolean isNative = NativeUtils.isNative();
                if (urls != null) {
                    // 遍历找到的对应扩展的文件url将其加入集合
                    while (urls.hasMoreElements()) {
                        URL url = urls.nextElement();
                        if (isNative) {
                            //In native mode, the address of each URL is the same instead of different paths, so it is necessary to set the ref to make it different
                            //动态修改jdk底层url对象的ref变量为可访问,让我们在用反射时访问私有变量
                            setRef(url);
                        }
                        set.add(url);
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
            // 存入缓存
            urlCache.put(fileName, set);
        }
        // 返回结果
        return urlCache.get(fileName);
    }

    public static void destroy() {
        synchronized (ClassLoaderResourceLoader.class) {
            classLoaderResourcesCache = null;
        }
    }

    private static void setRef(URL url) {
        try {
            Field field = URL.class.getDeclaredField("ref");
            field.setAccessible(true);
            field.set(url, UUID.randomUUID().toString());
        } catch (Throwable ignore) {
        }
    }


    // for test
    protected static SoftReference<Map<ClassLoader, Map<String, Set<URL>>>> getClassLoaderResourcesCache() {
        return classLoaderResourcesCache;
    }
}
