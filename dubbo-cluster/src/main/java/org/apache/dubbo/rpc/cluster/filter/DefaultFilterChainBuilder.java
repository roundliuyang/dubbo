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
package org.apache.dubbo.rpc.cluster.filter;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.common.extension.ExtensionDirector;
import org.apache.dubbo.common.extension.support.MultiInstanceActivateComparator;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.cluster.ClusterInvoker;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.apache.dubbo.rpc.model.ModuleModel;
import org.apache.dubbo.rpc.model.ScopeModel;
import org.apache.dubbo.rpc.model.ScopeModelUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Activate
public class DefaultFilterChainBuilder implements FilterChainBuilder {

    /**
     * build consumer/provider filter chain
     */
    @Override
    public <T> Invoker<T> buildInvokerChain(final Invoker<T> originalInvoker, String key, String group) {
        // originalInvoker代表真正的服务调用器
        Invoker<T> last = originalInvoker;
        URL url = originalInvoker.getUrl();
        List<ModuleModel> moduleModels = getModuleModelsFromUrl(url);
        List<Filter> filters;
        if (moduleModels != null && moduleModels.size() == 1) {
            // 类型Filter key为service.filter 分组为provider 所有提供者过滤器拉取
            filters = ScopeModelUtil.getExtensionLoader(Filter.class, moduleModels.get(0)).getActivateExtension(url, key, group);
        } else if (moduleModels != null && moduleModels.size() > 1) {
            filters = new ArrayList<>();
            List<ExtensionDirector> directors = new ArrayList<>();
            for (ModuleModel moduleModel : moduleModels) {
                List<Filter> tempFilters = ScopeModelUtil.getExtensionLoader(Filter.class, moduleModel).getActivateExtension(url, key, group);
                filters.addAll(tempFilters);
                directors.add(moduleModel.getExtensionDirector());
            }
            filters = sortingAndDeduplication(filters, directors);

        } else {
            filters = ScopeModelUtil.getExtensionLoader(Filter.class, null).getActivateExtension(url, key, group);
        }


        // 倒序拼接，将过滤器的调用对象添加到链表中  最后倒序遍历之后 last节点指向了调用链路链表头节点的对象
        if (!CollectionUtils.isEmpty(filters)) {
            for (int i = filters.size() - 1; i >= 0; i--) {
                final Filter filter = filters.get(i);
                final Invoker<T> next = last;
                last = new CopyOfFilterChainNode<>(originalInvoker, next, filter);
            }
            return new CallbackRegistrationInvoker<>(last, filters);
        }

        return last;
    }

    /**
     * build consumer cluster filter chain
     */
    @Override
    public <T> ClusterInvoker<T> buildClusterInvokerChain(final ClusterInvoker<T> originalInvoker, String key, String group) {
        ClusterInvoker<T> last = originalInvoker;
        URL url = originalInvoker.getUrl();
        List<ModuleModel> moduleModels = getModuleModelsFromUrl(url);
        List<ClusterFilter> filters;
        if (moduleModels != null && moduleModels.size() == 1) {
            // 通过扩展查询匹配的消费者过滤器列表这里可以查询4个
            filters = ScopeModelUtil.getExtensionLoader(ClusterFilter.class, moduleModels.get(0)).getActivateExtension(url, key, group);
        } else if (moduleModels != null && moduleModels.size() > 1) {
            filters = new ArrayList<>();
            List<ExtensionDirector> directors = new ArrayList<>();
            for (ModuleModel moduleModel : moduleModels) {
                List<ClusterFilter> tempFilters = ScopeModelUtil.getExtensionLoader(ClusterFilter.class, moduleModel).getActivateExtension(url, key, group);
                filters.addAll(tempFilters);
                directors.add(moduleModel.getExtensionDirector());
            }
            filters = sortingAndDeduplication(filters, directors);

        } else {
            filters = ScopeModelUtil.getExtensionLoader(ClusterFilter.class, null).getActivateExtension(url, key, group);
        }

        /*
            过滤器不为空则拼接到调用链表之中
            默认为4个过滤器：
            - ConsumerContextFilter
            - FutureFilter
            - MonitorClusterFilter
            - RouterSnapshotFilter
         */
        if (!CollectionUtils.isEmpty(filters)) {
            for (int i = filters.size() - 1; i >= 0; i--) {
                final ClusterFilter filter = filters.get(i);
                final Invoker<T> next = last;
                last = new CopyOfClusterFilterChainNode<>(originalInvoker, next, filter);
            }
            return new ClusterCallbackRegistrationInvoker<>(originalInvoker, last, filters);
        }

        return last;
    }

    private <T> List<T> sortingAndDeduplication(List<T> filters, List<ExtensionDirector> directors) {
        Map<Class<?>, T> filtersSet = new TreeMap<>(new MultiInstanceActivateComparator(directors));
        for (T filter : filters) {
            filtersSet.putIfAbsent(filter.getClass(), filter);
        }
        return new ArrayList<>(filtersSet.values());
    }

    /**
     * When the application-level service registration and discovery strategy is adopted, the URL will be of type InstanceAddressURL,
     * and InstanceAddressURL belongs to the application layer and holds the ApplicationModel,
     * but the filter is at the module layer and holds the ModuleModel,
     * so it needs to be based on the url in the ScopeModel type to parse out all the moduleModels held by the url
     * to obtain the filter configuration.
     *
     * @param url URL
     * @return All ModuleModels in the url
     */
    private List<ModuleModel> getModuleModelsFromUrl(URL url) {
        List<ModuleModel> moduleModels = null;
        ScopeModel scopeModel = url.getScopeModel();
        if (scopeModel instanceof ApplicationModel) {
            moduleModels = ((ApplicationModel) scopeModel).getPubModuleModels();
        } else if (scopeModel instanceof ModuleModel) {
            moduleModels = new ArrayList<>();
            moduleModels.add((ModuleModel) scopeModel);
        }
        return moduleModels;
    }

}
