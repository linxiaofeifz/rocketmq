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

import org.apache.rocketmq.client.common.ThreadLocalIndex;

import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class LatencyFaultToleranceImpl implements LatencyFaultTolerance<String> {

    /**
     * 对象故障信息表
     */
    private final ConcurrentHashMap<String, FaultItem> faultItemTable = new ConcurrentHashMap<String, FaultItem>(16);

    /**
     * 故障对象选择index
     */
    private final ThreadLocalIndex whichItemWorst = new ThreadLocalIndex();

    /**
     * <p>更新延迟和不可用时长</p>
     * @param name :
     * @param currentLatency :
     * @param notAvailableDuration :
     * @return void
    */
    @Override
    public void updateFaultItem(final String name, final long currentLatency, final long notAvailableDuration) {
        // 获取到旧的故障对象
        FaultItem old = this.faultItemTable.get(name);
        // 没有旧的故障对象
        if (null == old) {
            // 创建一个故障对象，设置延迟和开始可用时间
            final FaultItem faultItem = new FaultItem(name);
            faultItem.setCurrentLatency(currentLatency);
            faultItem.setStartTimestamp(System.currentTimeMillis() + notAvailableDuration);

            // 往故障对象表放入新创建的故障对象
            old = this.faultItemTable.putIfAbsent(name, faultItem);
            // 如果新创建的故障对象放入失败，说明故障表中存在旧的故障对象，直接更新旧故障对象的延迟和开始可用时间
            if (old != null) {
                old.setCurrentLatency(currentLatency);
                old.setStartTimestamp(System.currentTimeMillis() + notAvailableDuration);
            }
        } else {
            // 直接更新旧的故障对象的延迟和开始可用时间
            old.setCurrentLatency(currentLatency);
            old.setStartTimestamp(System.currentTimeMillis() + notAvailableDuration);
        }
    }

    /**
     * <p>故障对象是否可用</p>
     * @param name :
     * @return boolean
    */
    @Override
    public boolean isAvailable(final String name) {
        final FaultItem faultItem = this.faultItemTable.get(name);
        if (faultItem != null) {
            return faultItem.isAvailable();
        }
        return true;
    }

    /**
     * <p>移除故障对象</p>
     * @param name :
     * @return void
    */
    @Override
    public void remove(final String name) {
        this.faultItemTable.remove(name);
    }

    /**
     * <p>选择一个相对较好的故障对象</p>
     * @author linxf
     * @date  2019/9/3 10:14
     * @return java.lang.String
    */
    @Override
    public String pickOneAtLeast() {
        final Enumeration<FaultItem> elements = this.faultItemTable.elements();
        // 所有故障对象的数组
        List<FaultItem> tmpList = new LinkedList<FaultItem>();
        while (elements.hasMoreElements()) {
            final FaultItem faultItem = elements.nextElement();
            tmpList.add(faultItem);
        }

        if (!tmpList.isEmpty()) {
            // 打乱
            Collections.shuffle(tmpList);

            // 排序
            Collections.sort(tmpList);

            // 选择排序在前一半的故障对象
            final int half = tmpList.size() / 2;
            // 只有一个的情况
            if (half <= 0) {
                return tmpList.get(0).getName();
            } else {
                final int i = this.whichItemWorst.getAndIncrement() % half;
                return tmpList.get(i).getName();
            }
        }

        // 没有任何故障对象，返回null
        return null;
    }

    @Override
    public String toString() {
        return "LatencyFaultToleranceImpl{" +
            "faultItemTable=" + faultItemTable +
            ", whichItemWorst=" + whichItemWorst +
            '}';
    }

    /**
     *  <p>故障对象</p>
     *  <p>维护对象名称、延迟、开始可用时间</p>
     *  <p>对象相等，需要对象名称、延迟、开始可用时间都相同</p>
     */
    class FaultItem implements Comparable<FaultItem> {
        /**
         * 故障对象名称
         */
        private final String name;
        /**
         * 延迟
         */
        private volatile long currentLatency;
        /**
         * 开始可用时间
         */
        private volatile long startTimestamp;

        public FaultItem(final String name) {
            this.name = name;
        }

        /**
         * <p>对象比较</p>
         * <p>可用性 > 延迟 > 开始可用时间</p>
         * @param other :
         * @return int  升序
        */
        @Override
        public int compareTo(final FaultItem other) {
            if (this.isAvailable() != other.isAvailable()) {
                if (this.isAvailable())
                    return -1;

                if (other.isAvailable())
                    return 1;
            }

            if (this.currentLatency < other.currentLatency)
                return -1;
            else if (this.currentLatency > other.currentLatency) {
                return 1;
            }

            if (this.startTimestamp < other.startTimestamp)
                return -1;
            else if (this.startTimestamp > other.startTimestamp) {
                return 1;
            }

            return 0;
        }

        /**
         * <p>是否可用</p>
         * <p>当开始可用时间早于当前时间</p>
         * @return boolean
        */
        public boolean isAvailable() {
            return (System.currentTimeMillis() - startTimestamp) >= 0;
        }

        @Override
        public int hashCode() {
            int result = getName() != null ? getName().hashCode() : 0;
            result = 31 * result + (int) (getCurrentLatency() ^ (getCurrentLatency() >>> 32));
            result = 31 * result + (int) (getStartTimestamp() ^ (getStartTimestamp() >>> 32));
            return result;
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o)
                return true;
            if (!(o instanceof FaultItem))
                return false;

            final FaultItem faultItem = (FaultItem) o;

            if (getCurrentLatency() != faultItem.getCurrentLatency())
                return false;
            if (getStartTimestamp() != faultItem.getStartTimestamp())
                return false;
            return getName() != null ? getName().equals(faultItem.getName()) : faultItem.getName() == null;

        }

        @Override
        public String toString() {
            return "FaultItem{" +
                "name='" + name + '\'' +
                ", currentLatency=" + currentLatency +
                ", startTimestamp=" + startTimestamp +
                '}';
        }

        public String getName() {
            return name;
        }

        public long getCurrentLatency() {
            return currentLatency;
        }

        public void setCurrentLatency(final long currentLatency) {
            this.currentLatency = currentLatency;
        }

        public long getStartTimestamp() {
            return startTimestamp;
        }

        public void setStartTimestamp(final long startTimestamp) {
            this.startTimestamp = startTimestamp;
        }

    }
}
