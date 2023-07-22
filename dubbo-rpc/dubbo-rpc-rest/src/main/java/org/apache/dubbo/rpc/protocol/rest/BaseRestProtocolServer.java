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
package org.apache.dubbo.rpc.protocol.rest;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.utils.StringUtils;

import org.jboss.resteasy.spi.ResteasyDeployment;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.apache.dubbo.common.constants.CommonConstants.COMMA_SPLIT_PATTERN;
import static org.apache.dubbo.rpc.protocol.rest.Constants.EXTENSION_KEY;

/**
 * Rest server 抽象基类
 */
public abstract class BaseRestProtocolServer implements RestProtocolServer {

    private String address;

    private Map<String, Object> attributes = new ConcurrentHashMap<>();

    @Override
    public void start(URL url) {
        // 添加 MediaType
        getDeployment().getMediaTypeMappings().put("json", "application/json");
        getDeployment().getMediaTypeMappings().put("xml", "text/xml");
//        server.getDeployment().getMediaTypeMappings().put("xml", "application/xml");
        // 添加过滤器 RpcContextFilter
        getDeployment().getProviderClasses().add(RpcContextFilter.class.getName());
        // TODO users can override this mapper, but we just rely on the current priority strategy of resteasy
        // 添加异常匹配 RpcExceptionMapper
        getDeployment().getProviderClasses().add(RpcExceptionMapper.class.getName());

        // 从 `extension` 配置项，添加对应的组件（过滤器 Filter 、拦截器 Interceptor 、异常匹配器 ExceptionMapper 等等）
        loadProviders(url.getParameter(EXTENSION_KEY, ""));

        // 启动服务器
        doStart(url);
    }

    @Override
    public void deploy(Class resourceDef, Object resourceInstance, String contextPath) {
        // 部署 Service 服务。这里，如果类比 SpringMVC ，就是添加 @RestController 注解的类。
        if (StringUtils.isEmpty(contextPath)) {
            getDeployment().getRegistry().addResourceFactory(new DubboResourceFactory(resourceInstance, resourceDef));
        } else {
            getDeployment().getRegistry().addResourceFactory(new DubboResourceFactory(resourceInstance, resourceDef), contextPath);
        }
    }

    @Override
    public void undeploy(Class resourceDef) {
        getDeployment().getRegistry().removeRegistrations(resourceDef);
    }

    @Override
    public String getAddress() {
        return address;
    }

    @Override
    public void setAddress(String address) {
        this.address = address;
    }

    @Override
    public Map<String, Object> getAttributes() {
        return attributes;
    }

    protected void loadProviders(String value) {
        for (String clazz : COMMA_SPLIT_PATTERN.split(value)) {
            if (!StringUtils.isEmpty(clazz)) {
                getDeployment().getProviderClasses().add(clazz.trim());
            }
        }
    }

    protected abstract ResteasyDeployment getDeployment();

    protected abstract void doStart(URL url);
}
