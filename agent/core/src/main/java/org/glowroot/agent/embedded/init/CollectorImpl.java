/*
 * Copyright 2015-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glowroot.agent.embedded.init;

import java.io.File;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.collector.Collector;
import org.glowroot.agent.embedded.repo.AggregateDao;
import org.glowroot.agent.embedded.repo.EnvironmentDao;
import org.glowroot.agent.embedded.repo.GaugeValueDao;
import org.glowroot.agent.embedded.repo.TraceDao;
import org.glowroot.common.repo.ConfigRepository;
import org.glowroot.common.repo.util.AlertingService;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertKind;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.Environment;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.GaugeValue;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.LogEvent;

class CollectorImpl implements Collector {

    private static final Logger logger = LoggerFactory.getLogger(CollectorImpl.class);

    private static final String AGENT_ID = "";
    private static final String AGENT_DISPLAY = "";

    private final EnvironmentDao environmentDao;
    private final AggregateDao aggregateDao;
    private final TraceDao traceDao;
    private final GaugeValueDao gaugeValueDao;
    private final ConfigRepository configRepository;
    private final AlertingService alertingService;

    CollectorImpl(EnvironmentDao environmentDao, AggregateDao aggregateRepository,
            TraceDao traceRepository, GaugeValueDao gaugeValueRepository,
            ConfigRepository configRepository, AlertingService alertingService) {
        this.environmentDao = environmentDao;
        this.aggregateDao = aggregateRepository;
        this.traceDao = traceRepository;
        this.gaugeValueDao = gaugeValueRepository;
        this.configRepository = configRepository;
        this.alertingService = alertingService;
    }

    @Override
    public void init(File glowrootDir, File agentDir, Environment environment,
            AgentConfig agentConfig, AgentConfigUpdater agentConfigUpdater) throws Exception {
        environmentDao.store(environment);
    }

    @Override
    public void collectAggregates(AggregateReader aggregateReader) throws Exception {
        aggregateDao.store(aggregateReader);
        alertingService.checkForDeletedAlerts(AGENT_ID);
        for (AlertConfig alertConfig : configRepository.getAlertConfigs(AGENT_ID,
                AlertKind.TRANSACTION)) {
            try {
                alertingService.checkTransactionAlert(AGENT_ID, AGENT_DISPLAY, alertConfig,
                        aggregateReader.captureTime());
            } catch (InterruptedException e) {
                // shutdown request
                throw e;
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            }
        }
    }

    @Override
    public void collectGaugeValues(List<GaugeValue> gaugeValues) throws Exception {
        gaugeValueDao.store(gaugeValues);
        long maxCaptureTime = 0;
        for (GaugeValue gaugeValue : gaugeValues) {
            maxCaptureTime = Math.max(maxCaptureTime, gaugeValue.getCaptureTime());
        }
        alertingService.checkForDeletedAlerts(AGENT_ID);
        for (AlertConfig alertConfig : configRepository.getAlertConfigs(AGENT_ID,
                AlertKind.GAUGE)) {
            try {
                alertingService.checkGaugeAlert(AGENT_ID, AGENT_DISPLAY, alertConfig,
                        maxCaptureTime);
            } catch (InterruptedException e) {
                // shutdown request
                throw e;
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            }
        }
    }

    @Override
    public void collectTrace(TraceReader traceReader) throws Exception {
        traceDao.store(traceReader);
    }

    @Override
    public void log(LogEvent logEvent) {
        // do nothing, already logging locally through ConsoleAppender and RollingFileAppender
    }
}
