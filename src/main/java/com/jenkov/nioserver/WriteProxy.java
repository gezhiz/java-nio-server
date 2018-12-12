package com.jenkov.nioserver;

import java.util.Queue;

/**
 * Created by jjenkov on 22-10-2015.
 */
public class WriteProxy {

    private MessageBuffer messageBuffer = null;//消息工厂
    private Queue        writeQueue     = null;//待写的消息队列

    public WriteProxy(MessageBuffer messageBuffer, Queue writeQueue) {
        this.messageBuffer = messageBuffer;
        this.writeQueue = writeQueue;
    }

    public Message getMessage(){
        return this.messageBuffer.getMessage();
    }

    public boolean enqueue(Message message){
        return this.writeQueue.offer(message);//offer方法，如果Queue满了，则会返回false，而不会抛出异常
    }

}
