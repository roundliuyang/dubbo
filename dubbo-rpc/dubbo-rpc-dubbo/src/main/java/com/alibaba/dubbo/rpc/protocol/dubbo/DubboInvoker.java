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
package com.alibaba.dubbo.rpc.protocol.dubbo;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.utils.AtomicPositiveInteger;
import com.alibaba.dubbo.common.utils.ConfigUtils;
import com.alibaba.dubbo.remoting.RemotingException;
import com.alibaba.dubbo.remoting.TimeoutException;
import com.alibaba.dubbo.remoting.exchange.ExchangeClient;
import com.alibaba.dubbo.remoting.exchange.ResponseFuture;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcContext;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.RpcInvocation;
import com.alibaba.dubbo.rpc.RpcResult;
import com.alibaba.dubbo.rpc.protocol.AbstractInvoker;
import com.alibaba.dubbo.rpc.support.RpcUtils;

import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * DubboInvoker
 */
public class DubboInvoker<T> extends AbstractInvoker<T> {

    /**
     * 远程通信客户端数组
     */
    private final ExchangeClient[] clients;

    /**
     * 使用的 {@link #clients} 的位置
     */
    private final AtomicPositiveInteger index = new AtomicPositiveInteger();

    /**
     * 版本
     */
    private final String version;

    private final ReentrantLock destroyLock = new ReentrantLock();

    /**
     * Invoker 集合，从 {@link DubboProtocol#invokers} 获取
     */
    private final Set<Invoker<?>> invokers;

    public DubboInvoker(Class<T> serviceType, URL url, ExchangeClient[] clients) {
        this(serviceType, url, clients, null);
    }

    public DubboInvoker(Class<T> serviceType, URL url, ExchangeClient[] clients, Set<Invoker<?>> invokers) {
        /**
         * 调用父类构造方法。该方法中，会将 interface group version token timeout 添加到公用的隐式传参 AbstractInvoker.attachment 属性
         */
        super(serviceType, url, new String[]{Constants.INTERFACE_KEY, Constants.GROUP_KEY, Constants.TOKEN_KEY, Constants.TIMEOUT_KEY});
        this.clients = clients;
        // get version.
        this.version = url.getParameter(Constants.VERSION_KEY, Constants.DEFAULT_VERSION);
        this.invokers = invokers;
    }

    /**
     * 调用服务
     * @param invocation
     * @return
     * @throws Throwable
     */
    @Override
    protected Result doInvoke(final Invocation invocation) throws Throwable {
        RpcInvocation inv = (RpcInvocation) invocation;
        // 获得方法名
        final String methodName = RpcUtils.getMethodName(invocation);
        // 获得 `path`( 服务名 )，`version`
        inv.setAttachment(Constants.PATH_KEY, getUrl().getPath());
        inv.setAttachment(Constants.VERSION_KEY, version);

        // 获得 ExchangeClient 对象
        ExchangeClient currentClient;
        if (clients.length == 1) {
            currentClient = clients[0];
        } else {
            currentClient = clients[index.getAndIncrement() % clients.length];
        }
        // 远程调用
        try {
            // 获得是否异步调用
            boolean isAsync = RpcUtils.isAsync(getUrl(), invocation);
            // 获得是否单向调用
            boolean isOneway = RpcUtils.isOneway(getUrl(), invocation);
            // 获得超时时间
            int timeout = getUrl().getMethodParameter(methodName, Constants.TIMEOUT_KEY, Constants.DEFAULT_TIMEOUT);
            // 单向调用
            if (isOneway) {
                boolean isSent = getUrl().getMethodParameter(methodName, Constants.SENT_KEY, false);
                // 发送消息，而不是请求
                currentClient.send(inv, isSent);
                // 设置 RpcContext.future = null ，无需 FutureFilter ，异步回调
                RpcContext.getContext().setFuture(null);
                // 创建 RpcResult 对象，空返回
                return new RpcResult();
            // 异步调用
            } else if (isAsync) {
                // 发送请求
                ResponseFuture future = currentClient.request(inv, timeout);
                // 调用 RpcContext#setFuture(future) 方法，在 FutureFitler 中，异步回调
                RpcContext.getContext().setFuture(new FutureAdapter<Object>(future));
                // 创建 RpcResult 对象，空返回
                return new RpcResult();
            // 同步调用
            } else {
                // 设置 RpcContext.future = null ，无需 FutureFilter ，异步回调
                RpcContext.getContext().setFuture(null);
                /**
                 * 调用 ExchangeClient#request(invocation, timeout) 方法，发送请求
                 * 调用 ResponseFuture#get() 方法，阻塞等待，返回结果
                 */
                return (Result) currentClient.request(inv, timeout).get();
            }
        } catch (TimeoutException e) {
            throw new RpcException(RpcException.TIMEOUT_EXCEPTION, "Invoke remote method timeout. method: " + invocation.getMethodName() + ", provider: " + getUrl() + ", cause: " + e.getMessage(), e);
        } catch (RemotingException e) {
            throw new RpcException(RpcException.NETWORK_EXCEPTION, "Failed to invoke remote method: " + invocation.getMethodName() + ", provider: " + getUrl() + ", cause: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean isAvailable() {
        if (!super.isAvailable())
            return false;
        for (ExchangeClient client : clients) {
            if (client.isConnected() && !client.hasAttribute(Constants.CHANNEL_ATTRIBUTE_READONLY_KEY)) {
                //cannot write == not Available ?
                return true;
            }
        }
        return false;
    }

    @Override
    public void destroy() {
        // in order to avoid closing a client multiple times, a counter is used in case of connection per jvm, every
        // time when client.close() is called, counter counts down once, and when counter reaches zero, client will be
        // closed.
        if (super.isDestroyed()) {
            return;
        } else {
            // double check to avoid dup close
            destroyLock.lock();
            try {
                if (super.isDestroyed()) {
                    return;
                }
                super.destroy();
                if (invokers != null) {
                    invokers.remove(this);
                }
                for (ExchangeClient client : clients) {
                    try {
                        client.close(ConfigUtils.getServerShutdownTimeout());
                    } catch (Throwable t) {
                        logger.warn(t.getMessage(), t);
                    }
                }

            } finally {
                destroyLock.unlock();
            }
        }
    }
}
