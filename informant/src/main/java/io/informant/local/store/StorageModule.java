/**
 * Copyright 2011-2013 the original author or authors.
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
package io.informant.local.store;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ScheduledExecutorService;

import com.google.common.base.Ticker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.informant.common.Clock;
import io.informant.config.ConfigModule;
import io.informant.config.ConfigService;
import io.informant.markers.OnlyUsedByTests;
import io.informant.markers.ThreadSafe;
import io.informant.snapshot.SnapshotSink;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@ThreadSafe
public class StorageModule {

    private static final Logger logger = LoggerFactory.getLogger(StorageModule.class);

    private final RollingFile rollingFile;
    private final SnapshotDao snapshotDao;

    public StorageModule(Ticker ticker, Clock clock, File dataDir, ConfigModule configModule,
            DataSourceModule dataSourceModule, ScheduledExecutorService scheduledExecutor)
            throws Exception {
        ConfigService configService = configModule.getConfigService();
        int rollingSizeMb = configService.getGeneralConfig().getRollingSizeMb();
        DataSource dataSource = dataSourceModule.getDataSource();
        rollingFile = new RollingFile(new File(dataDir, "informant.rolling.db"),
                rollingSizeMb * 1024, scheduledExecutor, ticker);
        snapshotDao = new SnapshotDao(dataSource, rollingFile, clock);
        new SnapshotReaper(configService, snapshotDao, clock).start(scheduledExecutor);
    }

    public RollingFile getRollingFile() {
        return rollingFile;
    }

    public SnapshotDao getSnapshotDao() {
        return snapshotDao;
    }

    public SnapshotSink getSnapshotSink() {
        return snapshotDao;
    }

    @OnlyUsedByTests
    public void close() {
        logger.debug("close()");
        try {
            rollingFile.close();
        } catch (IOException e) {
            // warning only since it occurs during shutdown anyways
            logger.warn(e.getMessage(), e);
        }
    }
}