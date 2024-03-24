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
package com.alibaba.dubbo.remoting;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.Adaptive;
import com.alibaba.dubbo.common.extension.SPI;

/**
 * Transporter. (SPI, Singleton, ThreadSafe)
 * 网络传输接口
 *
 * <p>
 * <a href="http://en.wikipedia.org/wiki/Transport_Layer">Transport Layer</a>
 * <a href="http://en.wikipedia.org/wiki/Client%E2%80%93server_model">Client/Server</a>
 *
 * @see com.alibaba.dubbo.remoting.Transporters
 */
@SPI("netty")      // Dubbo SPI 拓展点，默认为 "netty" 。注意，此处的 netty 对应的是 netty3 ，因为 Dubbo 项目在开发时，netty4 并未发布。配置方式见 《Dubbo 用户指南 —— Netty4》 文档。
public interface Transporter {

    /**
     * Bind a server.
     * 绑定一个服务器
     *
     * @param url     server url
     * @param handler 通道处理器
     * @return server
     * @throws RemotingException
     * @see com.alibaba.dubbo.remoting.Transporters#bind(URL, ChannelHandler...)
     */
    @Adaptive({Constants.SERVER_KEY, Constants.TRANSPORTER_KEY})        // 基于 Dubbo SPI Adaptive 机制，加载对应的 Server 实现，使用 URL.server 或 URL.transporter 属性。
    Server bind(URL url, ChannelHandler handler) throws RemotingException;

    /**
     * Connect to a server.
     *
     * @param url     server url
     * @param handler 通道处理器
     * @return client 客户端
     * @throws RemotingException
     * @see com.alibaba.dubbo.remoting.Transporters#connect(URL, ChannelHandler...)
     */
    @Adaptive({Constants.CLIENT_KEY, Constants.TRANSPORTER_KEY})               // 基于 Dubbo SPI Adaptive 机制，加载对应的 Client 实现，使用 URL.client 或 URL.transporter 属性。
    Client connect(URL url, ChannelHandler handler) throws RemotingException;

}