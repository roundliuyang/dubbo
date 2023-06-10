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
package org.apache.dubbo.registry.client.migration;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.status.reporter.FrameworkStatusReportService;
import org.apache.dubbo.registry.client.migration.model.MigrationRule;
import org.apache.dubbo.registry.client.migration.model.MigrationStep;

public class MigrationRuleHandler<T> {
    public static final String DUBBO_SERVICEDISCOVERY_MIGRATION = "dubbo.application.migration.step";
    private static final Logger logger = LoggerFactory.getLogger(MigrationRuleHandler.class);

    private MigrationClusterInvoker<T> migrationInvoker;
    private MigrationStep currentStep;
    private Float currentThreshold = 0f;
    private URL consumerURL;

    public MigrationRuleHandler(MigrationClusterInvoker<T> invoker, URL url) {
        this.migrationInvoker = invoker;
        this.consumerURL = url;
    }

    public synchronized void doMigrate(MigrationRule rule) {
        // 默认情况下这个类型是MigrationInvoker
        if (migrationInvoker instanceof ServiceDiscoveryMigrationInvoker) {
            refreshInvoker(MigrationStep.FORCE_APPLICATION, 1.0f, rule);
            return;
        }

        // 迁移步骤，MigrationStep 一共有3种枚举情况：FORCE_INTERFACE, APPLICATION_FIRST, FORCE_APPLICATION
        // initial step : APPLICATION_FIRST
        MigrationStep step = MigrationStep.APPLICATION_FIRST;
        float threshold = -1f;

        try {
            // 获取配置的类型 默认走APPLICATION_FIRST
            step = rule.getStep(consumerURL);
            threshold = rule.getThreshold(consumerURL);
        } catch (Exception e) {
            logger.error("Failed to get step and threshold info from rule: " + rule, e);
        }

        // 刷洗调用器对象 来进行决策服务发现模式
        if (refreshInvoker(step, threshold, rule)) {
            // refresh success, update rule
            setMigrationRule(rule);
        }
    }

    private boolean refreshInvoker(MigrationStep step, Float threshold, MigrationRule newRule) {
        if (step == null || threshold == null) {
            throw new IllegalStateException("Step or threshold of migration rule cannot be null");
        }
        MigrationStep originStep = currentStep;

        if ((currentStep == null || currentStep != step) || !currentThreshold.equals(threshold)) {
            boolean success = true;
            /**
             * 可以看到这个代码做了判断的逻辑分别对应了Dubbo3消费者迁移的一个状态逻辑： 三种状态分别如下枚举类型： 当前共存在三种状态，
             * FORCE_INTERFACE（强制接口级）
             * APPLICATION_FIRST（应用级优先）
             * FORCE_APPLICATION（强制应用级）
             * 通过代码我们可以看到默认情况下都会走APPLICATION_FIRST（应用级优先）的策略，
             * 这里我们也重点来说 APPLICATION_FIRST（应用级优先）来看下Dubbo3是如何决策使用接口级还是应用级发现模型来兼容迁移的服务的
             */
            switch (step) {
                case APPLICATION_FIRST:
                    // 默认和配置了应用级优先的服务发现则走这里
                    migrationInvoker.migrateToApplicationFirstInvoker(newRule);
                    break;
                case FORCE_APPLICATION:
                    // 配置了应用级服务发现则走这里
                    success = migrationInvoker.migrateToForceApplicationInvoker(newRule);
                    break;
                case FORCE_INTERFACE:
                    // 配置了接口级服务发现则走这里
                default:
                    success = migrationInvoker.migrateToForceInterfaceInvoker(newRule);
            }

            if (success) {
                setCurrentStepAndThreshold(step, threshold);
                logger.info("Succeed Migrated to " + step + " mode. Service Name: " + consumerURL.getDisplayServiceKey());
                report(step, originStep, "true");
            } else {
                // migrate failed, do not save new step and rule
                logger.warn("Migrate to " + step + " mode failed. Probably not satisfy the threshold you set "
                        + threshold + ". Please try re-publish configuration if you still after check.");
                report(step, originStep, "false");
            }

            return success;
        }
        // ignore if step is same with previous, will continue override rule for MigrationInvoker
        return true;
    }

    private void report(MigrationStep step, MigrationStep originStep, String success) {
        FrameworkStatusReportService reportService =
            consumerURL.getOrDefaultApplicationModel().getBeanFactory().getBean(FrameworkStatusReportService.class);

        if (reportService.hasReporter()) {
            reportService.reportMigrationStepStatus(
                reportService.createMigrationStepReport(consumerURL.getServiceInterface(), consumerURL.getVersion(),
                            consumerURL.getGroup(), String.valueOf(originStep), String.valueOf(step), success));
        }
    }

    private void setMigrationRule(MigrationRule rule) {
        this.migrationInvoker.setMigrationRule(rule);
    }

    private Float getMigrationThreshold(MigrationRule rule, Float threshold) {
        Float configuredThreshold = rule.getThreshold(consumerURL);
        threshold = configuredThreshold == null ? threshold : configuredThreshold;
        return threshold;
    }

    private void setCurrentStepAndThreshold(MigrationStep currentStep, Float currentThreshold) {
        if (currentThreshold != null) {
            this.currentThreshold = currentThreshold;
        }
        if (currentStep != null) {
            this.currentStep = currentStep;
            this.migrationInvoker.setMigrationStep(currentStep);
        }
    }

    // for test purpose
    public MigrationStep getMigrationStep() {
        return currentStep;
    }
}
