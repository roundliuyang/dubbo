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

package org.apache.dubbo.rpc.proxy;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.profiler.Profiler;
import org.apache.dubbo.common.profiler.ProfilerEntry;
import org.apache.dubbo.common.profiler.ProfilerSwitch;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.dubbo.rpc.RpcServiceContext;

import java.util.Map;

import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_TIMEOUT;
import static org.apache.dubbo.common.constants.CommonConstants.TIMEOUT_KEY;

public class InvocationUtil {
    private static final Logger logger = LoggerFactory.getLogger(InvokerInvocationHandler.class);

    public static Object invoke(Invoker<?> invoker, RpcInvocation rpcInvocation) throws Throwable {

        // consumer://172.26.240.92/org.apache.dubbo.demo.DemoService?application=dubbo-demo-api-consumer&background=false&dubbo=2.0.2&generic=true&interface=org.apache.dubbo.demo.DemoService&pid=11028&register.ip=172.26.240.92&release=&side=consumer&sticky=false&timestamp=1686478196792
        URL url = invoker.getUrl();

        // org.apache.dubbo.demo.DemoService
        String serviceKey = url.getServiceKey();
        rpcInvocation.setTargetServiceUniqueName(serviceKey);

        // invoker.getUrl() returns consumer url.
        RpcServiceContext.getServiceContext().setConsumerUrl(url);

        // 默认前后开启性能分析
        if (ProfilerSwitch.isEnableSimpleProfiler()) {
            // 创建一个InternalThreadLocal<ProfilerEntry> bizProfiler线程本地对象来存储性能信息
            // 首次进入这个性能实体为空
            ProfilerEntry parentProfiler = Profiler.getBizProfiler();
            ProfilerEntry bizProfiler;

            // 首次为空走下面逻辑创建个性能分析实体
            // 这里如果是第二个调用invoker方法则将性能数据串起来前面的放到parent ProfilerEntry 内部用链表结构实现一个性能链路
            if (parentProfiler != null) {
                bizProfiler = Profiler.enter(parentProfiler,
                    "Receive request. Client invoke begin. ServiceKey: " + serviceKey + " MethodName:" + rpcInvocation.getMethodName());
            } else {
                bizProfiler = Profiler.start("Receive request. Client invoke begin. ServiceKey: " + serviceKey + " " + "MethodName:" + rpcInvocation.getMethodName());
            }
            rpcInvocation.put(Profiler.PROFILER_KEY, bizProfiler);
            try {
                // 第一个invoker类型为MigrationInvoker。其实前面我们已经说过是如何决策应用级还是接口级的调用默认走应用级下面直接看应用级相关的invoker链路
                return invoker.invoke(rpcInvocation).recreate();
            } finally {
                Profiler.release(bizProfiler);
                int timeout;
                Object timeoutKey = rpcInvocation.getObjectAttachmentWithoutConvert(TIMEOUT_KEY);
                if (timeoutKey instanceof Integer) {
                    timeout = (Integer) timeoutKey;
                } else {
                    timeout = url.getMethodPositiveParameter(rpcInvocation.getMethodName(),
                        TIMEOUT_KEY,
                        DEFAULT_TIMEOUT);
                }
                long usage = bizProfiler.getEndTime() - bizProfiler.getStartTime();
                if ((usage / (1000_000L * ProfilerSwitch.getWarnPercent())) > timeout) {
                    StringBuilder attachment = new StringBuilder();
                    for (Map.Entry<String, Object> entry : rpcInvocation.getObjectAttachments().entrySet()) {
                        attachment.append(entry.getKey()).append("=").append(entry.getValue()).append(";\n");
                    }

                    logger.warn(String.format(
                        "[Dubbo-Consumer] execute service %s#%s cost %d.%06d ms, this invocation almost (maybe already) timeout. Timeout: %dms\n" + "invocation context:\n%s" + "thread info: \n%s",
                        rpcInvocation.getProtocolServiceKey(),
                        rpcInvocation.getMethodName(),
                        usage / 1000_000,
                        usage % 1000_000,
                        timeout,
                        attachment,
                        Profiler.buildDetail(bizProfiler)));
                }
            }
        }
        return invoker.invoke(rpcInvocation).recreate();
    }
}
