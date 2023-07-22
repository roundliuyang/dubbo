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
package org.apache.dubbo.demo.rest.provider;

import org.apache.dubbo.config.*;
import org.apache.dubbo.config.bootstrap.DubboBootstrap;
import org.apache.dubbo.demo.DemoRestService;
import org.apache.dubbo.demo.DemoService;

import java.util.concurrent.CountDownLatch;

public class Application {
    public static void main(String[] args) throws Exception {
        if (isClassic(args)) {
            startWithExport();
        } else {
            startWithBootstrap();
        }
    }

    private static boolean isClassic(String[] args) {
        return args.length > 0 && "classic".equalsIgnoreCase(args[0]);
    }

    private static void startWithBootstrap() {
        // 创建一个服务配置对象
        ServiceConfig<DemoRestServiceImpl> service = new ServiceConfig<>();
        // 为服务配置下服务接口和服务实现,下面两行用来初始化对象就不详细说了
        service.setInterface(DemoRestService.class);
        service.setRef(new DemoRestServiceImpl());

        // 这个DubboBootstrap就是用来启动Dubbo服务的.类似于Netty的Bootstrap类型和ServerBootstrap启动器
        DubboBootstrap bootstrap = DubboBootstrap.getInstance();
        // 初始化应用配置
        bootstrap.application(new ApplicationConfig("dubbo-demo-api-rest-provider"))
            // 初始化注册中心配置
            .registry(new RegistryConfig("zookeeper://127.0.0.1:2181"))
            // 初始化协议配置
            .protocol(new ProtocolConfig("rest", 8081))
            // 初始化服务配置
            .service(service)
            .start()
            // 启动
            .await();
    }

    private static void startWithExport() throws InterruptedException {
        ServiceConfig<DemoRestServiceImpl> service = new ServiceConfig<>();
        service.setInterface(DemoService.class);
        service.setRef(new DemoRestServiceImpl());
        service.setApplication(new ApplicationConfig("dubbo-demo-api-rest-provider"));
        service.setRegistry(new RegistryConfig("zookeeper://127.0.0.1:2181"));
        service.setMetadataReportConfig(new MetadataReportConfig("zookeeper://127.0.0.1:2181"));
        service.export();

        System.out.println("dubbo service started");
        new CountDownLatch(1).await();
    }
}
