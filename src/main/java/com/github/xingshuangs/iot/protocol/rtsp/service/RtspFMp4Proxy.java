/*
 * MIT License
 *
 * Copyright (c) 2021-2099 Oscura (xingshuang) <xingshuang_cool@163.com>
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.github.xingshuangs.iot.protocol.rtsp.service;


import com.github.xingshuangs.iot.common.IObjectByteArray;
import com.github.xingshuangs.iot.common.buff.ByteReadBuff;
import com.github.xingshuangs.iot.exceptions.RtspCommException;
import com.github.xingshuangs.iot.protocol.mp4.model.*;
import com.github.xingshuangs.iot.protocol.rtp.enums.EFrameType;
import com.github.xingshuangs.iot.protocol.rtp.enums.EH264NaluType;
import com.github.xingshuangs.iot.protocol.rtp.model.frame.H264VideoFrame;
import com.github.xingshuangs.iot.protocol.rtp.model.payload.SeqParameterSet;
import com.github.xingshuangs.iot.protocol.rtsp.model.sdp.RtspTrackInfo;
import com.github.xingshuangs.iot.utils.HexUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * @author xingshuang
 */
@Slf4j
public class RtspFMp4Proxy {

    private final Object objLock = new Object();

    /**
     * RTSP客户端
     */
    private final RtspClient client;

    /**
     * 轨道信息
     */
    private RtspTrackInfo trackInfo;

    /**
     * 接收帧数据的序列号
     */
    private long sequenceNumber = 1;

    /**
     * 数据缓存
     */
    private final ConcurrentLinkedQueue<IObjectByteArray> buffers = new ConcurrentLinkedQueue<>();

    /**
     * FMp4数据事件
     */
    private Consumer<byte[]> fmp4DataHandle;

    /**
     * codec的处理事件
     */
    private Consumer<String> codecHandle;

    /**
     * 销毁的处理事件
     */
    private Runnable destroyHandle;

    /**
     * 是否终止
     */
    private volatile boolean terminal = false;

    /**
     * MP4的头
     */
    private Mp4Header mp4Header;

    /**
     * 轨道信息
     */
    private Mp4TrackInfo mp4TrackInfo;

    /**
     * 是否异步步发送
     */
    private boolean asyncSend = false;

    /**
     * 上一次的H264的视频帧
     */
    private H264VideoFrame lastFrame;

    /**
     * 缓存视频帧数据，主要用于重新排序
     */
    private final List<H264VideoFrame> gop = new ArrayList<>();

    /**
     * 异步执行的对象
     */
    private CompletableFuture<Void> future;

    /**
     * 线程池执行服务，单线程
     */
    private ExecutorService executorService;

    public Mp4Header getMp4Header() {
        return mp4Header;
    }

    public Mp4TrackInfo getMp4TrackInfo() {
        return mp4TrackInfo;
    }

    public void onFmp4DataHandle(Consumer<byte[]> fmp4DataHandle) {
        this.fmp4DataHandle = fmp4DataHandle;
    }

    public void onCodecHandle(Consumer<String> codecHandle) {
        this.codecHandle = codecHandle;
    }

    public void onDestroyHandle(Runnable destroyHandle) {
        this.destroyHandle = destroyHandle;
    }

    public RtspFMp4Proxy(RtspClient client) {
        this(client, false);
    }

    public RtspFMp4Proxy(RtspClient client, boolean asyncSend) {
        this.client = client;
        this.client.onFrameHandle(x -> {
            H264VideoFrame f = (H264VideoFrame) x;
            this.frameHandle(f);
        });
        this.client.onDestroyHandle(() -> {
            if (this.destroyHandle != null) {
                this.destroyHandle.run();
            }
        });
        this.asyncSend = asyncSend;
        if (this.asyncSend) {
            this.executorService = Executors.newSingleThreadExecutor();
            this.future = CompletableFuture.runAsync(this::executeHandle, this.executorService);
        }
    }

    /**
     * 处理SPS，只处理一次
     *
     * @param frame 帧数据
     */
    private void handleSPS(H264VideoFrame frame) {
        if (this.trackInfo != null && this.trackInfo.getSps() != null) {
            return;
        }
        byte[] spsBytes = frame.getFrameSegment();
        if (spsBytes == null || spsBytes.length < 4) {
            throw new RtspCommException("SPS is not exist");
        }
        ByteReadBuff buff = new ByteReadBuff(spsBytes);
        byte[] bytes = buff.getBytes(1, 3);
        String codec = "avc1." + HexUtil.toHexString(bytes, "", false);

        SeqParameterSet sps = SeqParameterSet.createSPS(spsBytes);

        // 处理trackInfo
        this.trackInfo = this.client.getTrackInfo();
        this.trackInfo.setSps(spsBytes);
        this.trackInfo.setCodec(codec);
        this.trackInfo.setWidth(sps.getWidth());
        this.trackInfo.setHeight(sps.getHeight());

        if (this.codecHandle != null) {
            this.codecHandle.accept(codec);
        }
    }

    /**
     * 处理PPS，只处理一次
     *
     * @param frame 帧数据
     */
    private void handlePPS(H264VideoFrame frame) {
        if (this.trackInfo != null && this.trackInfo.getPps() != null) {
            return;
        }
        // 处理trackInfo
        this.trackInfo = this.client.getTrackInfo();
        this.trackInfo.setPps(frame.getFrameSegment());
    }

    /**
     * 处理Mp4，只处理一次
     */
    private void handleMp4Header() {
        if (this.mp4Header != null) {
            return;
        }
        this.mp4TrackInfo = this.toMp4TrackInfo(this.client.getTrackInfo());
        log.debug(this.mp4TrackInfo.toString());
        this.mp4Header = new Mp4Header(mp4TrackInfo);
        this.addFMp4Data(mp4Header);
    }

    /**
     * 帧处理事件
     *
     * @param frame 数据帧
     */
    private void frameHandle(H264VideoFrame frame) {
        if (frame.getFrameType() == EFrameType.AUDIO) {
            return;
        }
        if (frame.getNaluType() == EH264NaluType.SEI
                || frame.getNaluType() == EH264NaluType.AUD) {
            return;
        } else if (frame.getNaluType() == EH264NaluType.SPS) {
            this.handleSPS(frame);
            return;
        } else if (frame.getNaluType() == EH264NaluType.PPS) {
            this.handlePPS(frame);
            return;
        }
        // 处理map4的header，只处理1次
        this.handleMp4Header();

        // 这里的作用是缓存10个帧数据，重新排序，因为可能收到的帧时间戳不是按时间顺序排列
        this.gop.add(frame);
        if (this.gop.size() < 10) {
            return;
        }
        this.gop.sort((a, b) -> (int) (a.getTimestamp() - b.getTimestamp()));
        H264VideoFrame videoFrame = this.gop.remove(0);

        if (this.lastFrame == null) {
            this.lastFrame = videoFrame;
        }
        if (this.lastFrame.getTimestamp() > videoFrame.getTimestamp()) {
            // 出现一帧数据时间戳小于之前的一帧的时间戳
            log.warn("The timestamp of a frame is smaller than the timestamp of the previous frame");
        }
        this.lastFrame = videoFrame;

        Mp4SampleData sampleData = new Mp4SampleData();
        sampleData.setData(videoFrame.getFrameSegment());
        sampleData.setTimestamp(videoFrame.getTimestamp());
        sampleData.getFlags().setDependedOn(videoFrame.getNaluType() == EH264NaluType.IDR_SLICE ? 2 : 1);
        sampleData.getFlags().setIsNonSync(videoFrame.getNaluType() == EH264NaluType.IDR_SLICE ? 0 : 1);

        if (videoFrame.getNaluType() == EH264NaluType.IDR_SLICE) {
            // 当前是IDR帧，发送并清空之前的数据，然后发送IDR帧
            if (!this.mp4TrackInfo.getSampleData().isEmpty()) {
                this.addSampleData();
            }
            this.mp4TrackInfo.getSampleData().add(sampleData);
            this.addSampleData();
        } else {
            // 当前不是IDR帧，等数据量足够的时候再发送
            this.mp4TrackInfo.getSampleData().add(sampleData);
            if (this.mp4TrackInfo.getSampleData().size() >= 5) {
                this.addSampleData();
            }
        }
    }

    private void addSampleData() {
        Mp4SampleData first = this.mp4TrackInfo.getSampleData().get(0);
        this.addFMp4Data(new Mp4MoofBox(this.sequenceNumber, first.getTimestamp(), this.mp4TrackInfo));
        this.addFMp4Data(new Mp4MdatBox(this.mp4TrackInfo.totalSampleData()));
        // 更新mp4TrackInfo，用新的数据副本
        this.mp4TrackInfo = this.toMp4TrackInfo(this.trackInfo);
        this.sequenceNumber++;
    }

    /**
     * 数据转换，包装成Mp4需要的轨道信息
     *
     * @param track 轨道信息
     * @return Mp4TrackInfo
     */
    private Mp4TrackInfo toMp4TrackInfo(RtspTrackInfo track) {
        Mp4TrackInfo info = new Mp4TrackInfo();
        info.setId(track.getId());
        info.setType(track.getType());
        info.setCodec(track.getCodec());
        info.setTimescale(track.getTimescale());
        info.setDuration(track.getDuration());
        info.setWidth(track.getWidth());
        info.setHeight(track.getHeight());
        info.setSps(track.getSps());
        info.setPps(track.getPps());
        return info;
    }

    /**
     * 添加FMp4数据
     *
     * @param iObjectByteArray 数据
     */
    private void addFMp4Data(IObjectByteArray iObjectByteArray) {
        if (this.asyncSend) {
            this.buffers.offer(iObjectByteArray);
            synchronized (this.objLock) {
                this.objLock.notifyAll();
            }
        } else {
            if (this.fmp4DataHandle != null) {
                this.fmp4DataHandle.accept(iObjectByteArray.toByteArray());
            }
        }
    }

    /**
     * 事件执行
     */
    private void executeHandle() {
        // 开启代理服务端发送FMp4字节数据的异步线程
        log.debug("Start the asynchronous thread that sends FMp4 bytes of data from the proxy server");
        while (!this.terminal) {
            // 没数据的时候等待
            while (this.buffers.isEmpty() && !this.terminal) {
                synchronized (this.objLock) {
                    try {
                        this.objLock.wait();
                    } catch (InterruptedException e) {
                        // NOOP
                    }
                }
            }
            // 有数据的时候发送出去
            int size = this.buffers.size();
            for (int i = 0; i < size; i++) {
                IObjectByteArray pop = this.buffers.poll();
                if (this.fmp4DataHandle != null && pop != null) {
                    try {
                        this.fmp4DataHandle.accept(pop.toByteArray());
                    } catch (Exception e) {
                        log.error(e.getMessage(), e);
                    }
                }
            }
        }
        // 关闭代理服务端发送FMp4字节数据的异步线程
        log.debug("Shut down the asynchronous thread that sends FMp4 bytes of data from the proxy server");
    }

    /**
     * 开始
     *
     * @return 异步结果
     */
    public CompletableFuture<Void> start() {
        // 开启FMp4代理服务端，模式[{}]，地址[{}]
        log.info("Open FMp4 agent server, mode [{}], address [{}]", this.asyncSend ? "async" : "sync", this.client.getUri());
        return this.client.start();
    }

    /**
     * 结束
     */
    public void stop() {
        if (this.executorService != null) {
            this.executorService.shutdown();
        }
        if (this.asyncSend) {
            this.terminal = true;
            synchronized (this.objLock) {
                this.objLock.notifyAll();
            }
            if (this.future != null && !this.future.isDone()) {
                this.future.join();
            }
        }
        this.client.stop();
        // 关闭FMp4代理服务端，地址[{}]
        log.info("Close FMp4 agent server, address [{}]", this.client.getUri());
    }
}
