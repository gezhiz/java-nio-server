package com.jenkov.nioserver.http;

import com.jenkov.nioserver.IMessageReader;
import com.jenkov.nioserver.Message;
import com.jenkov.nioserver.MessageBuffer;
import com.jenkov.nioserver.Socket;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by jjenkov on 18-10-2015.
 */
public class HttpMessageReader implements IMessageReader {

    private MessageBuffer messageBuffer    = null;//Message工厂，工厂里面记录了下一个要扩容的大小

    private List<Message> completeMessages = new ArrayList<Message>();//完成读取的消息列表
    private Message       nextMessage      = null;//处理中的消息

    public HttpMessageReader() {
    }

    @Override
    public void init(MessageBuffer readMessageBuffer) {
        this.messageBuffer        = readMessageBuffer;
        this.nextMessage          = messageBuffer.getMessage();
        this.nextMessage.metaData = new HttpHeaders();
    }

    @Override
    public void read(Socket socket, ByteBuffer byteBuffer) throws IOException {
        int bytesRead = socket.read(byteBuffer);//如果读取完成所有的数据，则socket会被标记已经读完数据
        byteBuffer.flip();

        if(byteBuffer.remaining() == 0){
            byteBuffer.clear();
            return;
        }

        //把byteBuffer的所有内容写入nextMessage
        int copiedCount = this.nextMessage.writeToMessage(byteBuffer);//如果nextMessage不够大，则扩容
        if (copiedCount == -1) {
            System.out.println("扩容失败，消息被丢弃");
            return;
        }

        //解析nextMessage是否有结束标记
        int endIndex = HttpUtil.parseHttpRequest(this.nextMessage.sharedArray, this.nextMessage.offset, this.nextMessage.offset + this.nextMessage.length, (HttpHeaders) this.nextMessage.metaData);
        if(endIndex != -1){
            //找到了消息的结束标志，可以添加一条消息
            Message message = this.messageBuffer.getMessage();//新生成一条空的message
            message.metaData = new HttpHeaders();

            //从nextMessage中把多余的消息内容移到message
            message.writePartialMessageToMessage(nextMessage, endIndex);
            //nextMessage就是一条完整的消息了
            completeMessages.add(nextMessage);
            nextMessage = message;
        }
        byteBuffer.clear();
    }


    @Override
    public List<Message> getMessages() {
        return this.completeMessages;
    }

}
