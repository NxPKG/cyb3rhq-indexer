/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

/*
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.threadpool;

import org.opensearch.common.settings.Setting;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.unit.SizeValue;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.common.util.concurrent.OpenSearchExecutors;
import org.opensearch.common.util.concurrent.ThreadContext;
import org.opensearch.node.Node;

import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A builder for executors that automatically adjust the queue length as needed, depending on
 * Little's Law. See https://en.wikipedia.org/wiki/Little's_law for more information.
 *
 * @opensearch.internal
 */
public final class AutoQueueAdjustingExecutorBuilder extends ExecutorBuilder<AutoQueueAdjustingExecutorBuilder.AutoExecutorSettings> {

    private final Setting<Integer> sizeSetting;
    private final Setting<Integer> queueSizeSetting;
    private final Setting<Integer> minQueueSizeSetting;
    private final Setting<Integer> maxQueueSizeSetting;
    private final Setting<TimeValue> targetedResponseTimeSetting;
    private final Setting<Integer> frameSizeSetting;
    private final AtomicReference<RunnableTaskExecutionListener> runnableTaskListener;

    AutoQueueAdjustingExecutorBuilder(
        final Settings settings,
        final String name,
        final int size,
        final int initialQueueSize,
        final int minQueueSize,
        final int maxQueueSize,
        final int frameSize
    ) {
        this(settings, name, size, initialQueueSize, minQueueSize, maxQueueSize, frameSize, null);
    }

    AutoQueueAdjustingExecutorBuilder(
        final Settings settings,
        final String name,
        final int size,
        final int initialQueueSize,
        final int minQueueSize,
        final int maxQueueSize,
        final int frameSize,
        final AtomicReference<RunnableTaskExecutionListener> runnableTaskListener
    ) {
        super(name);
        final String prefix = "thread_pool." + name;
        final String sizeKey = settingsKey(prefix, "size");
        this.sizeSetting = new Setting<>(
            sizeKey,
            s -> Integer.toString(size),
            s -> Setting.parseInt(s, 1, applyHardSizeLimit(settings, name), sizeKey),
            Setting.Property.NodeScope
        );
        final String queueSizeKey = settingsKey(prefix, "queue_size");
        final String minSizeKey = settingsKey(prefix, "min_queue_size");
        final String maxSizeKey = settingsKey(prefix, "max_queue_size");
        final String frameSizeKey = settingsKey(prefix, "auto_queue_frame_size");
        final String targetedResponseTimeKey = settingsKey(prefix, "target_response_time");
        this.targetedResponseTimeSetting = Setting.timeSetting(
            targetedResponseTimeKey,
            TimeValue.timeValueSeconds(1),
            TimeValue.timeValueMillis(10),
            Setting.Property.NodeScope,
            Setting.Property.Deprecated
        );
        this.queueSizeSetting = Setting.intSetting(queueSizeKey, initialQueueSize, Setting.Property.NodeScope);
        // These temp settings are used to validate the min and max settings below
        Setting<Integer> tempMaxQueueSizeSetting = Setting.intSetting(
            maxSizeKey,
            maxQueueSize,
            Setting.Property.NodeScope,
            Setting.Property.Deprecated
        );
        Setting<Integer> tempMinQueueSizeSetting = Setting.intSetting(
            minSizeKey,
            minQueueSize,
            Setting.Property.NodeScope,
            Setting.Property.Deprecated
        );

        this.minQueueSizeSetting = new Setting<>(
            minSizeKey,
            Integer.toString(minQueueSize),
            s -> Setting.parseInt(s, 0, minSizeKey),
            new Setting.Validator<Integer>() {

                @Override
                public void validate(final Integer value) {

                }

                @Override
                public void validate(final Integer value, final Map<Setting<?>, Object> settings) {
                    if (value > (int) settings.get(tempMaxQueueSizeSetting)) {
                        throw new IllegalArgumentException(
                            "Failed to parse value ["
                                + value
                                + "] for setting ["
                                + minSizeKey
                                + "] must be <= "
                                + settings.get(tempMaxQueueSizeSetting)
                        );
                    }
                }

                @Override
                public Iterator<Setting<?>> settings() {
                    final List<Setting<?>> settings = Collections.singletonList(tempMaxQueueSizeSetting);
                    return settings.iterator();
                }

            },
            Setting.Property.NodeScope
        );
        this.maxQueueSizeSetting = new Setting<>(
            maxSizeKey,
            Integer.toString(maxQueueSize),
            s -> Setting.parseInt(s, 0, maxSizeKey),
            new Setting.Validator<Integer>() {

                @Override
                public void validate(Integer value) {

                }

                @Override
                public void validate(final Integer value, final Map<Setting<?>, Object> settings) {
                    if (value < (int) settings.get(tempMinQueueSizeSetting)) {
                        throw new IllegalArgumentException(
                            "Failed to parse value ["
                                + value
                                + "] for setting ["
                                + minSizeKey
                                + "] must be >= "
                                + settings.get(tempMinQueueSizeSetting)
                        );
                    }
                }

                @Override
                public Iterator<Setting<?>> settings() {
                    final List<Setting<?>> settings = Collections.singletonList(tempMinQueueSizeSetting);
                    return settings.iterator();
                }

            },
            Setting.Property.NodeScope,
            Setting.Property.Deprecated
        );
        this.frameSizeSetting = Setting.intSetting(
            frameSizeKey,
            frameSize,
            100,
            Setting.Property.NodeScope,
            Setting.Property.Deprecated,
            Setting.Property.Deprecated
        );
        this.runnableTaskListener = runnableTaskListener;
    }

    @Override
    public List<Setting<?>> getRegisteredSettings() {
        return Arrays.asList(
            sizeSetting,
            queueSizeSetting,
            minQueueSizeSetting,
            maxQueueSizeSetting,
            frameSizeSetting,
            targetedResponseTimeSetting
        );
    }

    @Override
    AutoExecutorSettings getSettings(Settings settings) {
        final String nodeName = Node.NODE_NAME_SETTING.get(settings);
        final int size = sizeSetting.get(settings);
        final int initialQueueSize = queueSizeSetting.get(settings);
        final int minQueueSize = minQueueSizeSetting.get(settings);
        final int maxQueueSize = maxQueueSizeSetting.get(settings);
        final int frameSize = frameSizeSetting.get(settings);
        final TimeValue targetedResponseTime = targetedResponseTimeSetting.get(settings);
        return new AutoExecutorSettings(nodeName, size, initialQueueSize, minQueueSize, maxQueueSize, frameSize, targetedResponseTime);
    }

    @Override
    ThreadPool.ExecutorHolder build(final AutoExecutorSettings settings, final ThreadContext threadContext) {
        int size = settings.size;
        int initialQueueSize = settings.initialQueueSize;
        int minQueueSize = settings.minQueueSize;
        int maxQueueSize = settings.maxQueueSize;
        int frameSize = settings.frameSize;
        TimeValue targetedResponseTime = settings.targetedResponseTime;
        final ThreadFactory threadFactory = OpenSearchExecutors.daemonThreadFactory(
            OpenSearchExecutors.threadName(settings.nodeName, name())
        );
        final ExecutorService executor = OpenSearchExecutors.newAutoQueueFixed(
            settings.nodeName + "/" + name(),
            size,
            initialQueueSize,
            minQueueSize,
            maxQueueSize,
            frameSize,
            targetedResponseTime,
            threadFactory,
            threadContext,
            runnableTaskListener
        );
        // TODO: in a subsequent change we hope to extend ThreadPool.Info to be more specific for the thread pool type
        final ThreadPool.Info info = new ThreadPool.Info(
            name(),
            ThreadPool.ThreadPoolType.FIXED_AUTO_QUEUE_SIZE,
            size,
            size,
            null,
            new SizeValue(initialQueueSize)
        );
        return new ThreadPool.ExecutorHolder(executor, info);
    }

    @Override
    String formatInfo(ThreadPool.Info info) {
        return String.format(
            Locale.ROOT,
            "name [%s], size [%d], queue size [%s]",
            info.getName(),
            info.getMax(),
            info.getQueueSize() == null ? "unbounded" : info.getQueueSize()
        );
    }

    static final class AutoExecutorSettings extends ExecutorBuilder.ExecutorSettings {

        final int size;
        final int initialQueueSize;
        final int minQueueSize;
        final int maxQueueSize;
        final int frameSize;
        final TimeValue targetedResponseTime;

        AutoExecutorSettings(
            final String nodeName,
            final int size,
            final int initialQueueSize,
            final int minQueueSize,
            final int maxQueueSize,
            final int frameSize,
            final TimeValue targetedResponseTime
        ) {
            super(nodeName);
            this.size = size;
            this.initialQueueSize = initialQueueSize;
            this.minQueueSize = minQueueSize;
            this.maxQueueSize = maxQueueSize;
            this.frameSize = frameSize;
            this.targetedResponseTime = targetedResponseTime;
        }

    }

}
