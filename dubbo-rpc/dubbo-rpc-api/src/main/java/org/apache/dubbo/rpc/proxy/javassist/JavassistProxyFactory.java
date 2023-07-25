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
package org.apache.dubbo.rpc.proxy.javassist;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.bytecode.Proxy;
import org.apache.dubbo.common.bytecode.Wrapper;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.proxy.AbstractProxyFactory;
import org.apache.dubbo.rpc.proxy.AbstractProxyInvoker;
import org.apache.dubbo.rpc.proxy.InvokerInvocationHandler;
import org.apache.dubbo.rpc.proxy.jdk.JdkProxyFactory;

import java.util.Arrays;

/**
 * 基于 Javassist 代理工厂实现类
 * JavassistRpcProxyFactory
 */
public class JavassistProxyFactory extends AbstractProxyFactory {
    private final static Logger logger = LoggerFactory.getLogger(JavassistProxyFactory.class);
    private final JdkProxyFactory jdkProxyFactory = new JdkProxyFactory();

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getProxy(Invoker<T> invoker, Class<?>[] interfaces) {
        try {
            /**
             * 调用 Proxy#getProxy(interface)方法，获得 Proxy 对象，
             * 调用 Proxy#newInstance(InvocationHandler) 方法，获得 proxy 对象。
             * 其中传入的的参数是是 InvokerInvocationHandler 对象，通过这样的方式，让 proxy 和真正的逻辑代码解耦
             */
            return (T) Proxy.getProxy(interfaces).newInstance(new InvokerInvocationHandler(invoker));
        } catch (Throwable fromJavassist) {
            // try fall back to JDK proxy factory
            try {
                T proxy = jdkProxyFactory.getProxy(invoker, interfaces);
                logger.error("Failed to generate proxy by Javassist failed. Fallback to use JDK proxy success. " +
                    "Interfaces: " + Arrays.toString(interfaces), fromJavassist);
                return proxy;
            } catch (Throwable fromJdk) {
                logger.error("Failed to generate proxy by Javassist failed. Fallback to use JDK proxy is also failed. " +
                    "Interfaces: " + Arrays.toString(interfaces) + " Javassist Error.", fromJavassist);
                logger.error("Failed to generate proxy by Javassist failed. Fallback to use JDK proxy is also failed. " +
                    "Interfaces: " + Arrays.toString(interfaces) + " JDK Error.", fromJdk);
                throw fromJavassist;
            }
        }
    }

    @Override
    public <T> Invoker<T> getInvoker(T proxy, Class<T> type, URL url) {
        try {
            // TODO Wrapper cannot handle this scenario correctly: the classname contains '$'
            // 创建实际服务提供者的代理类型，代理类型后缀为DubboWrap在这里类型为 link.elastic.dubbo.entity.DemoServiceImplDubboWrap0
            final Wrapper wrapper = Wrapper.getWrapper(proxy.getClass().getName().indexOf('$') < 0 ? proxy.getClass() : type);
            // 创建一个匿名内部类对象 继承自AbstractProxyInvoker的Invoker对象
            return new AbstractProxyInvoker<T>(proxy, type, url) {
                // 在该方法中，调用 Wrapper#invokeMethod(...) 方法，从而调用 Service 的方法
                @Override
                protected Object doInvoke(T proxy, String methodName,
                                          Class<?>[] parameterTypes,
                                          Object[] arguments) throws Throwable {
                    return wrapper.invokeMethod(proxy, methodName, parameterTypes, arguments);
                }
            };
        } catch (Throwable fromJavassist) {
            // try fall back to JDK proxy factory
            try {
                Invoker<T> invoker = jdkProxyFactory.getInvoker(proxy, type, url);
                logger.error("Failed to generate invoker by Javassist failed. Fallback to use JDK proxy success. " +
                    "Interfaces: " + type, fromJavassist);
                // log out error
                return invoker;
            } catch (Throwable fromJdk) {
                logger.error("Failed to generate invoker by Javassist failed. Fallback to use JDK proxy is also failed. " +
                    "Interfaces: " + type + " Javassist Error.", fromJavassist);
                logger.error("Failed to generate invoker by Javassist failed. Fallback to use JDK proxy is also failed. " +
                    "Interfaces: " + type + " JDK Error.", fromJdk);
                throw fromJavassist;
            }
        }
    }

}
