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

package org.apache.rocketmq.client.latency;

import org.apache.rocketmq.client.impl.producer.TopicPublishInfo;
import org.apache.rocketmq.client.log.ClientLogger;
import org.apache.rocketmq.logging.InternalLogger;
import org.apache.rocketmq.common.message.MessageQueue;

public class MQFaultStrategy {
    private final static InternalLogger log = ClientLogger.getLog();
    // 延迟故障容错，维护每个broker的发送消息的延迟
    // key brokerName
    private final LatencyFaultTolerance<String> latencyFaultTolerance = new LatencyFaultToleranceImpl();

    // 延迟容错开关，默认关闭
    private boolean sendLatencyFaultEnable = false;

    // 延迟级别数组
    private long[] latencyMax = {50L, 100L, 550L, 1000L, 2000L, 3000L, 15000L};

    // 不可用时长数组
    private long[] notAvailableDuration = {0L, 0L, 30000L, 60000L, 120000L, 180000L, 600000L};

    public long[] getNotAvailableDuration() {
        return notAvailableDuration;
    }

    public void setNotAvailableDuration(final long[] notAvailableDuration) {
        this.notAvailableDuration = notAvailableDuration;
    }

    public long[] getLatencyMax() {
        return latencyMax;
    }

    public void setLatencyMax(final long[] latencyMax) {
        this.latencyMax = latencyMax;
    }

    public boolean isSendLatencyFaultEnable() {
        return sendLatencyFaultEnable;
    }

    public void setSendLatencyFaultEnable(final boolean sendLatencyFaultEnable) {
        this.sendLatencyFaultEnable = sendLatencyFaultEnable;
    }

    /** 
     * <p>根据topic发布信息，选择一个消息队列</p>
     * @param tpInfo :
     * @param lastBrokerName : 
     * @return org.apache.rocketmq.common.message.MessageQueue 
    */
    public MessageQueue selectOneMessageQueue(final TopicPublishInfo tpInfo, final String lastBrokerName) {
        // 开启容错时
        // 优先获取可用队列，其次选择一个broker获取队列，最差返回任意一个broker的队列
        if (this.sendLatencyFaultEnable) {
            try {
                int index = tpInfo.getSendWhichQueue().getAndIncrement();
                for (int i = 0; i < tpInfo.getMessageQueueList().size(); i++) {
                    // 队列下标
                    int pos = Math.abs(index++) % tpInfo.getMessageQueueList().size();
                    if (pos < 0)
                        pos = 0;
                    MessageQueue mq = tpInfo.getMessageQueueList().get(pos);
                    // 获取 brokerName=lastBrokerName && 可用的一个消息队列
                    if (latencyFaultTolerance.isAvailable(mq.getBrokerName())) {
                        // 第一次选择队列的时候，lastBrokerName == null
                        if (null == lastBrokerName || mq.getBrokerName().equals(lastBrokerName))
                            return mq;
                    }
                }

                // 选择一个相对较好的broker
                final String notBestBroker = latencyFaultTolerance.pickOneAtLeast();
                int writeQueueNums = tpInfo.getQueueIdByBroker(notBestBroker);
                // 获取消息队列，不考虑队列可用性
                if (writeQueueNums > 0) {
                    final MessageQueue mq = tpInfo.selectOneMessageQueue();
                    if (notBestBroker != null) {
                        mq.setBrokerName(notBestBroker);
                        mq.setQueueId(tpInfo.getSendWhichQueue().getAndIncrement() % writeQueueNums);
                    }
                    return mq;
                } else {
                    latencyFaultTolerance.remove(notBestBroker);
                }
            } catch (Exception e) {
                log.error("Error occurred when selecting message queue", e);
            }

            // 选择一个消息队列，不考虑队列可用性
            return tpInfo.selectOneMessageQueue();
        }

        // 未开启容错时
        // 获取lastBrokerName的一个消息队列，不考虑队列可用性
        return tpInfo.selectOneMessageQueue(lastBrokerName);
    }

    /**
     * <p>更新延迟容错信息</p>
     * @param brokerName :
     * @param currentLatency : 延迟
     * @param isolation : 是否隔离。开启隔离时，默认延迟30000。目前主要用于发送消息异常时
     * @return void
    */
    public void updateFaultItem(final String brokerName, final long currentLatency, boolean isolation) {
        // 只有开启容错，才需要更新容错信息
        if (this.sendLatencyFaultEnable) {
            // 计算对应延迟不可用时间
            long duration = computeNotAvailableDuration(isolation ? 30000 : currentLatency);
            this.latencyFaultTolerance.updateFaultItem(brokerName, currentLatency, duration);
        }
    }

    /**
     * <p>计算延迟对应的不可用时间</p>
     * @param currentLatency : 延迟
     * @return long   不可用时间
    */
    private long computeNotAvailableDuration(final long currentLatency) {
        for (int i = latencyMax.length - 1; i >= 0; i--) {
            if (currentLatency >= latencyMax[i])
                return this.notAvailableDuration[i];
        }

        return 0;
    }
}
