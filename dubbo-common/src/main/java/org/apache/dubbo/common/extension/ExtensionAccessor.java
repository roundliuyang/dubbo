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

/**
 * 扩展的统一访问器
 * Uniform accessor for extension
 */
public interface ExtensionAccessor {

    /**
     * 用于获取扩展加载管理器ExtensionDirector对象
     */
    ExtensionDirector getExtensionDirector();

    /**
     * 获取扩展对象ExtensionLoader
     *
     * 模型对象(FrameworkModel)-–> 扩展访问器(ExtensionAccessor) —> 作用域扩展加载程序管理器(ExtensionDirector)
     * 这个getExtensionDirector()方法来源于FrameworkModel的抽象父类型ScopeModel中的getExtensionDirector()
     */
    default <T> ExtensionLoader<T> getExtensionLoader(Class<T> type) {
        return this.getExtensionDirector().getExtensionLoader(type);
    }

    /**
     * 根据扩展名字获取具体扩展对象
     */
    default <T> T getExtension(Class<T> type, String name) {
        ExtensionLoader<T> extensionLoader = getExtensionLoader(type);
        return extensionLoader != null ? extensionLoader.getExtension(name) : null;
    }

    /**
     * 获取自适应扩展对象
     */
    default <T> T getAdaptiveExtension(Class<T> type) {
        ExtensionLoader<T> extensionLoader = getExtensionLoader(type);
        return extensionLoader != null ? extensionLoader.getAdaptiveExtension() : null;
    }

    /**
     * 获取默认扩展对象
     */
    default <T> T getDefaultExtension(Class<T> type) {
        ExtensionLoader<T> extensionLoader = getExtensionLoader(type);
        return extensionLoader != null ? extensionLoader.getDefaultExtension() : null;
    }

}
