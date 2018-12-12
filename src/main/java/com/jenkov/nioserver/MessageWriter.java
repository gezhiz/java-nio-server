package com.jenkov.nioserver;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by jjenkov on 21-10-2015.
 */
public class MessageWriter {

    private List<Message> writeQueue   = new ArrayList<>();
    private Message  messageInProgress = null;//待写入的消息
    private int      bytesWritten      =    0;//已经写入的byte的长度

    public MessageWriter() {
    }

    public void enqueue(Message message) {
        if(this.messageInProgress == null){
            this.messageInProgress = message;
        } else {
            this.writeQueue.add(message);
        }
    }

    public void write(Socket socket, ByteBuffer byteBuffer) throws IOException {
        //messageInProgress.offset是消息本身的偏移量，message天生自带offset,预留了部分空间
        byteBuffer.put(this.messageInProgress.sharedArray, this.messageInProgress.offset + this.bytesWritten, this.messageInProgress.length - this.bytesWritten);
        byteBuffer.flip();

        this.bytesWritten += socket.write(byteBuffer);
        byteBuffer.clear();

        if(bytesWritten >= this.messageInProgress.length){
            //当前消息处理完成，则移除
            if(this.writeQueue.size() > 0){
                this.messageInProgress = this.writeQueue.remove(0);
            } else {
                this.messageInProgress = null;
                //todo unregister from selector

            }
        }
    }

    public boolean isEmpty() {
        return this.writeQueue.isEmpty() && this.messageInProgress == null;
    }

}
