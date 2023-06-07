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

import org.apache.dubbo.rpc.model.ApplicationModel;
import org.apache.dubbo.rpc.model.FrameworkModel;
import org.apache.dubbo.rpc.model.ModuleModel;

/**
 * 扩展SPI域,目前有FRAMEWORK,APPLICATION,MODULE,SELF
 * Extension SPI Scope
 * @see SPI
 * @see ExtensionDirector
 */
public enum ExtensionScope {

    /**
     * 扩展实例在框架内使用，与所有应用程序和模块共享。 框架范围SPI扩展只能获取FrameworkModel，无法获取ApplicationModel和ModuleModel。
     * 考虑： 一些SPI需要在框架内的应用程序之间共享数据 无状态SPI在框架内是安全共享的
     *
     * The extension instance is used within framework, shared with all applications and modules.
     *
     * <p>Framework scope SPI extension can only obtain {@link FrameworkModel},
     * cannot get the {@link ApplicationModel} and {@link ModuleModel}.</p>
     *
     * <p></p>
     * Consideration:
     * <ol>
     * <li>Some SPI need share data between applications inside framework</li>
     * <li>Stateless SPI is safe shared inside framework</li>
     * </ol>
     */
    FRAMEWORK,

    /**
     * 扩展实例在一个应用程序中使用，与应用程序的所有模块共享，不同的应用程序创建不同的扩展实例。
     * 应用范围SPI扩展可以获取FrameworkModel和ApplicationModel，无法获取ModuleModel。 考虑： 在框架内隔离不同应用程序中的扩展数据 在应用程序内部的所有模块之间共享扩展数据
     *
     * The extension instance is used within one application, shared with all modules of the application,
     * and different applications create different extension instances.
     *
     * <p>Application scope SPI extension can obtain {@link FrameworkModel} and {@link ApplicationModel},
     * cannot get the {@link ModuleModel}.</p>
     *
     * <p></p>
     * Consideration:
     * <ol>
     * <li>Isolate extension data in different applications inside framework</li>
     * <li>Share extension data between all modules inside application</li>
     * </ol>
     */
    APPLICATION,

    /**
     * 扩展实例在一个模块中使用，不同的模块创建不同的扩展实例。 模块范围SPI扩展可以获得FrameworkModel、ApplicationModel和ModuleModel。 考虑： 隔离应用程序内部不同模块中的扩展数据
     * The extension instance is used within one module, and different modules create different extension instances.
     *
     * <p>Module scope SPI extension can obtain {@link FrameworkModel}, {@link ApplicationModel} and {@link ModuleModel}.</p>
     *
     * <p></p>
     * Consideration:
     * <ol>
     * <li>Isolate extension data in different modules inside application</li>
     * </ol>
     */
    MODULE,

    /**
     * 自给自足，为每个作用域创建一个实例，用于特殊的SPI扩展，如ExtensionInjector
     * self-sufficient, creates an instance for per scope, for special SPI extension, like {@link ExtensionInjector}
     */
    SELF
}
