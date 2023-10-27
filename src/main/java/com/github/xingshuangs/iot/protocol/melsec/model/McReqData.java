package com.github.xingshuangs.iot.protocol.melsec.model;


import com.github.xingshuangs.iot.protocol.common.IObjectByteArray;
import com.github.xingshuangs.iot.protocol.melsec.enums.EMcCommand;
import lombok.Data;

/**
 * 协议体数据：公共请求数据
 *
 * @author xingshuang
 */
@Data
public class McReqData implements IObjectByteArray {

    /**
     * 指令，2个字节
     */
    protected EMcCommand command;

    /**
     * 子指令，2个字节
     */
    protected int subcommand;

    @Override
    public int byteArrayLength() {
        return 0;
    }

    @Override
    public byte[] toByteArray() {
        return new byte[0];
    }
}
