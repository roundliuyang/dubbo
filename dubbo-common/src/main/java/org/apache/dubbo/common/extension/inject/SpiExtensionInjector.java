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
package org.apache.dubbo.common.extension.inject;

import org.apache.dubbo.common.extension.ExtensionAccessor;
import org.apache.dubbo.common.extension.ExtensionInjector;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.extension.SPI;

/**
 * SpiExtensionInjector
 */
public class SpiExtensionInjector implements ExtensionInjector {
    public static final String NAME = "spi";

    private ExtensionAccessor extensionAccessor;

    @Override
    public void setExtensionAccessor(ExtensionAccessor extensionAccessor) {
        this.extensionAccessor = extensionAccessor;
    }

    @Override
    public <T> T getInstance(Class<T> type, String name) {
        // 如果是一个标准的被@SPI注解修饰的扩展接口则满足条件
        if (type.isInterface() && type.isAnnotationPresent(SPI.class)) {
            // 使用扩展访问器来获取对应类型的扩展加载器
            ExtensionLoader<T> loader = extensionAccessor.getExtensionLoader(type);
            if (loader == null) {
                return null;
            }
            // 使用对应类型的扩展加载器来加载自适应扩展 这个加载的扩展可以参考4.4.6小节
            if (!loader.getSupportedExtensions().isEmpty()) {
                return loader.getAdaptiveExtension();
            }
        }
        return null;
    }

}
